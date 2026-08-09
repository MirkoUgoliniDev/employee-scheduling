"""Browser interface for the wizard, using only the standard library.

The use case is a headless Raspberry Pi: start the wizard over SSH and complete
installation in a browser on the local PC while watching real-time progress.

No Flask and no pip: Debian bookworm blocks installing system Python packages
(PEP 668), and requiring that to be solved BEFORE installation would be a
circular dependency. ``http.server`` plus server-sent events are sufficient.
"""

import json
import os
import queue
import secrets
import smtplib
import ssl
import threading
from email.message import EmailMessage
from email.utils import parseaddr
from http.cookies import CookieError, SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

from lib.constants import SERVICE_NAME, WIZARD_VERSION
from lib.runner import get_abort_event
from lib.step_base import STEP_ICONS, Status

# Event queues for connected browsers. One queue per client: a slow browser must
# neither slow down other browsers nor cause them to lose lines.
_clients = []
_clients_lock = threading.Lock()
_state = {"running": False, "finished": False, "ok": False}
# Protect "check whether running, then start": without it, two close requests —
# a double-click is enough — would both pass and start two installations on the
# same machine.
_start_lock = threading.Lock()


def _claim_run() -> bool:
    """Claim a run. Return False if one is already in progress."""
    with _start_lock:
        if _state["running"]:
            return False
        _state["running"] = True
        return True


def _broadcast(event: dict) -> None:
    payload = json.dumps(event, ensure_ascii=False)
    with _clients_lock:
        for client in list(_clients):
            try:
                client.put_nowait(payload)
            except queue.Full:
                # The browser cannot keep up: discard the event rather than
                # blocking installation to update an interface.
                pass


PAGE = """<!doctype html>
<html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Employee Scheduling — Installazione</title>
<style>
 :root{--bg:#12141a;--panel:#1a1d26;--line:#2a2f3d;--fg:#e6e8ee;--mut:#8b93a7;
       --ok:#3fb950;--err:#f85149;--run:#58a6ff;--acc:#7c8cff}
 *{box-sizing:border-box} body{margin:0;background:var(--bg);color:var(--fg);
   font:15px/1.55 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
 header{padding:18px 22px;border-bottom:1px solid var(--line)}
 h1{margin:0;font-size:18px} .sub{color:var(--mut);font-size:13px;margin-top:4px}
 .wrap{display:flex;flex-wrap:wrap;gap:18px;padding:18px 22px;align-items:flex-start}
 .panel{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px}
 .steps{flex:1 1 320px;min-width:300px} .right{flex:2 1 460px;min-width:320px}
 .step{display:flex;gap:10px;padding:9px 0;border-bottom:1px solid var(--line)}
 .step:last-child{border-bottom:0}
 .ico{width:20px;text-align:center;font-size:16px}
 .nm{font-weight:600} .ds{color:var(--mut);font-size:12.5px}
 .msg{color:var(--mut);font-size:12.5px;margin-top:3px;white-space:pre-wrap}
 .DONE .ico{color:var(--ok)} .FAILED .ico{color:var(--err)}
 .RUNNING .ico{color:var(--run)} .SKIPPED .ico{color:var(--mut)}
 label{display:block;margin:10px 0 4px;font-size:13px;color:var(--mut)}
  input,select{width:100%;padding:9px 10px;background:#0e1017;color:var(--fg);
    border:1px solid var(--line);border-radius:7px;font-size:14px}
  input[type=checkbox]{width:auto;margin-right:7px}
 .row{display:flex;gap:12px;flex-wrap:wrap} .row>div{flex:1 1 160px}
 button{margin-top:16px;padding:11px 18px;background:var(--acc);color:#fff;border:0;
   border-radius:8px;font-size:15px;font-weight:600;cursor:pointer}
 button:disabled{opacity:.5;cursor:not-allowed}
 pre{background:#0b0d13;border:1px solid var(--line);border-radius:8px;padding:12px;
   max-height:46vh;overflow:auto;font-size:12.5px;white-space:pre-wrap;margin:14px 0 0}
 .note{font-size:12.5px;color:var(--mut);margin-top:12px}
 .done{border-color:var(--ok)} .fail{border-color:var(--err)}
</style></head><body>
<header><h1>Employee Scheduling — installazione</h1>
<div class="sub">wizard v__VER__ · __HOST__</div></header>
<div class="wrap">
 <div class="panel steps"><div id="steps"></div></div>
 <div class="panel right">
  <div class="row">
   <div><label>Motore dati</label><select id="engine">
     <option value="postgresql">PostgreSQL (consigliato per piu' utenti)</option>
     <option value="sqlite">SQLite (file singolo, piu' leggero)</option></select></div>
   <div><label>Porta HTTP</label><input id="port" type="number" value="__PORT__"></div>
  </div>
  <label>Pacchetto da installare (percorso sul server)</label>
  <input id="jar" value="__JAR__" placeholder="/home/pi/employee-scheduling-runner.jar">
  <label>Cartella dati, backup e registro</label>
  <input id="data_dir" value="__DATA__">
   <div class="row">
    <div><label>Server SMTP (facoltativo)</label><input id="smtp_host" placeholder="smtp-relay.brevo.com"></div>
    <div><label>Porta SMTP</label><input id="smtp_port" type="number" value="587"></div>
   </div>
   <div class="row">
    <div><label>Utente SMTP</label><input id="smtp_user"></div>
    <div><label>Password SMTP</label><input id="smtp_pass" type="password"></div>
   </div>
   <div class="row">
    <div><label>Mittente</label><input id="smtp_from" type="email" placeholder="nome@dominio.it"></div>
    <div><label>Email per il test</label><input id="smtp_recipient" type="email" placeholder="nome@dominio.it"></div>
   </div>
   <button id="smtp_test" type="button" style="background:#39405a">Prova invio email</button>
   <div id="smtp_result" class="note"></div>
   <div class="note">Senza SMTP il codice di registrazione compare solo nel registro del servizio.</div>
   <label><input id="demo_data" type="checkbox">Installa i dati dimostrativi</label>
  <button id="go">Installa</button>
  <button id="dry" style="background:#39405a">Simula soltanto</button>
  <pre id="log">In attesa…</pre>
 </div></div>
<script>
const STEPS = __STEPS__;
const TOKEN = __TOKEN__;
const endpoint = (path)=>path + "?token=" + encodeURIComponent(TOKEN);
document.getElementById("engine").value=__ENGINE__;
document.getElementById("demo_data").checked=__DEMO__;
const ICON = {PENDING:"○",RUNNING:"◎",DONE:"✓",FAILED:"✗",SKIPPED:"⊘"};
function draw(){
  document.getElementById("steps").innerHTML = STEPS.map((s,i)=>
    `<div class="step ${s.status}" id="st${i}"><div class="ico">${ICON[s.status]}</div>
     <div><div class="nm">${s.name}</div><div class="ds">${s.description}</div>
     ${s.message?`<div class="msg">${s.message}</div>`:""}</div></div>`).join("");
}
draw();
const logEl = document.getElementById("log");
function append(t){ if(logEl.textContent==="In attesa…") logEl.textContent="";
  logEl.textContent += t + "\\n"; logEl.scrollTop = logEl.scrollHeight; }
const es = new EventSource(endpoint("/stream"));
es.onmessage = (e)=>{ const d = JSON.parse(e.data);
  if(d.type==="log") append(d.line);
  else if(d.type==="step"){ const s=STEPS[d.index]; if(s){s.status=d.status; s.message=d.message||"";} draw(); }
  else if(d.type==="end"){ document.getElementById("go").disabled=false;
    document.getElementById("dry").disabled=false;
    append(d.ok ? "\\n=== Completato ===" : "\\n=== Interrotto: correggi e rilancia ===");
    if(d.url) append("Applicazione: " + d.url); }
};
function start(dry){
  document.getElementById("go").disabled=true; document.getElementById("dry").disabled=true;
  logEl.textContent="";
  fetch(endpoint("/start"),{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({dry_run:dry,
      engine:document.getElementById("engine").value,
      port:parseInt(document.getElementById("port").value,10),
      jar:document.getElementById("jar").value,
      data_dir:document.getElementById("data_dir").value,
      smtp_host:document.getElementById("smtp_host").value,
      smtp_port:parseInt(document.getElementById("smtp_port").value,10),
      smtp_user:document.getElementById("smtp_user").value,
      smtp_pass:document.getElementById("smtp_pass").value,
      smtp_from:document.getElementById("smtp_from").value,
      demo_data:document.getElementById("demo_data").checked})})
   .then(r=>r.json()).then(d=>{ if(d.error){ append("Errore: "+d.error);
      document.getElementById("go").disabled=false; document.getElementById("dry").disabled=false; }});
}
document.getElementById("go").onclick =()=>start(false);
document.getElementById("dry").onclick=()=>start(true);
document.getElementById("smtp_test").onclick=()=>{
  const out=document.getElementById("smtp_result");
  const button=document.getElementById("smtp_test");
  button.disabled=true; out.textContent="Connessione e invio in corso…";
  fetch(endpoint("/test-smtp"),{method:"POST",headers:{"Content-Type":"application/json"},
    body:JSON.stringify({smtp_host:document.getElementById("smtp_host").value,
      smtp_port:parseInt(document.getElementById("smtp_port").value,10),
      smtp_user:document.getElementById("smtp_user").value,
      smtp_pass:document.getElementById("smtp_pass").value,
      smtp_from:document.getElementById("smtp_from").value,
      smtp_recipient:document.getElementById("smtp_recipient").value})})
    .then(async r=>({status:r.status,data:await r.json()}))
    .then(({status,data})=>{out.textContent=data.message||data.error||("Errore HTTP "+status);})
    .catch(e=>{out.textContent="Test non riuscito: "+e;})
    .finally(()=>{button.disabled=false;});
};
</script></body></html>
"""

UNLOCK_PAGE = """<!doctype html>
<html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Employee Scheduling — Accesso al setup</title>
<style>
 :root{color-scheme:dark} *{box-sizing:border-box} body{margin:0;background:#12141a;color:#e6e8ee;
 font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;display:grid;place-items:center;min-height:100vh}
 main{width:min(420px,calc(100% - 32px));background:#1a1d26;border:1px solid #2a2f3d;border-radius:12px;padding:24px}
 h1{font-size:20px;margin:0 0 8px} p{color:#aab1c2} label{display:block;margin:18px 0 6px}
 input{width:100%;padding:12px;background:#0e1017;color:#fff;border:1px solid #39405a;border-radius:8px;
 font:600 20px/1.2 ui-monospace,Consolas,monospace;text-align:center;letter-spacing:.15em;text-transform:uppercase}
 button{width:100%;margin-top:14px;padding:12px;background:#7c8cff;color:#fff;border:0;border-radius:8px;font-weight:700}
 .error{color:#ff8b87}
</style></head><body><main>
<h1>Employee Scheduling — setup</h1>
<p>Inserisci il codice temporaneo mostrato nel terminale del Raspberry.</p>
__ERROR__
<form method="post" action="/unlock" autocomplete="off">
 <label for="code">Codice di accesso</label>
 <input id="code" name="code" maxlength="9" autofocus required placeholder="ABCD-EFGH">
 <button type="submit">Apri il wizard</button>
</form></main></body></html>"""


def _valid_email(value: str) -> bool:
    address = parseaddr(value or "")[1]
    return bool(address and "@" in address and address.rsplit("@", 1)[1])


def _smtp_test(payload: dict) -> str:
    host = str(payload.get("smtp_host") or "").strip()
    username = str(payload.get("smtp_user") or "").strip()
    password = str(payload.get("smtp_pass") or "")
    sender = str(payload.get("smtp_from") or username).strip()
    recipient = str(payload.get("smtp_recipient") or sender).strip()
    try:
        port = int(payload.get("smtp_port") or 587)
    except (TypeError, ValueError) as exc:
        raise ValueError("La porta SMTP non e' valida.") from exc
    if not host or not username or not password:
        raise ValueError("Inserisci server, utente e password SMTP.")
    if not 1 <= port <= 65535:
        raise ValueError("La porta SMTP deve essere compresa tra 1 e 65535.")
    if not _valid_email(sender) or not _valid_email(recipient):
        raise ValueError("Mittente e destinatario del test devono essere email valide.")

    message = EmailMessage()
    message["From"] = sender
    message["To"] = recipient
    message["Subject"] = "Employee Scheduling SMTP test"
    message.set_content("SMTP configuration test completed successfully.")
    context = ssl.create_default_context()
    with smtplib.SMTP(host, port, timeout=20) as client:
        client.ehlo()
        client.starttls(context=context)
        client.ehlo()
        client.login(username, password)
        client.send_message(message)
    return f"Email di prova accettata dal server SMTP e inviata a {recipient}."


def run_webui(steps, runner, sysinfo, config, port: int,
              host: str = "127.0.0.1") -> int:
    ip = sysinfo.primary_ip() or "localhost"
    forced_dry_run = runner.dry_run
    token = secrets.token_urlsafe(32) if host not in ("127.0.0.1", "localhost", "::1") else ""
    access_code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(8))

    def steps_json():
        return json.dumps([{ "name": s.name, "description": s.description,
                             "status": s.status.name, "message": s.message}
                           for s in steps], ensure_ascii=False)

    def on_status(step, status: Status):
        _broadcast({"type": "step", "index": steps.index(step),
                    "status": status.name, "message": step.message})

    runner.set_log_callback(lambda line: _broadcast({"type": "log", "line": line}))

    def worker(payload: dict):
        # _claim_run already set running before responding to the browser; only
        # reset the previous run's results here.
        _state.update(finished=False, ok=False)
        ok = False
        try:
            # In command-line simulation, the page cannot promote itself to a
            # real installation: someone using --dry-run expects no changes,
            # regardless of which button they press.
            runner.dry_run = forced_dry_run or bool(payload.get("dry_run"))
            for key in ("engine", "port", "jar", "data_dir", "smtp_host",
                        "smtp_port", "smtp_user", "smtp_pass", "smtp_from"):
                if payload.get(key) not in (None, ""):
                    config[key] = payload[key]
            config["demo_data"] = bool(payload.get("demo_data"))
            # Reset step states: otherwise a second run would show the previous
            # run's green checks and obscure the current run's progress.
            for step in steps:
                step.status = Status.PENDING
                step.message = ""
            _broadcast({"type": "log", "line": "Avvio dell'installazione…"})
            from wizard import run_steps
            ok = run_steps(steps, runner, sysinfo, config, on_status=on_status)
        except Exception as exc:  # noqa: BLE001
            _broadcast({"type": "log", "line": f"Errore imprevisto: {exc}"})
        finally:
            # Without this finally, an exception would leave running=True
            # forever. Every later start would report "installation already in
            # progress," with buttons disabled until the wizard was killed.
            _state.update(running=False, finished=True, ok=ok)
            _broadcast({"type": "end", "ok": ok,
                        "url": f"http://{ip}:{config.get('port')}"
                               if ok and not runner.dry_run else ""})
            if ok and not runner.dry_run:
                # Leave enough time for the final SSE event to reach the page,
                # then remove the privileged temporary server automatically.
                threading.Timer(3, server.shutdown).start()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *_):
            pass  # the wizard log already contains the step log

        def _send(self, code: int, body: bytes, ctype: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            if not token:
                return True
            supplied = parse_qs(urlsplit(self.path).query).get("token", [""])[0]
            if supplied and secrets.compare_digest(supplied, token):
                return True
            try:
                cookie = SimpleCookie(self.headers.get("Cookie", ""))
            except CookieError:
                return False
            stored = cookie.get("employee_setup_token")
            return bool(stored and secrets.compare_digest(stored.value, token))

        def _unlock_page(self, error: str = "", status: int = 200):
            message = f'<p class="error">{error}</p>' if error else ""
            body = UNLOCK_PAGE.replace("__ERROR__", message).encode("utf-8")
            self._send(status, body, "text/html; charset=utf-8")

        def _unlock(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                values = parse_qs(self.rfile.read(length).decode("utf-8", errors="replace"))
                supplied = values.get("code", [""])[0].replace("-", "").strip().upper()
            except (TypeError, ValueError):
                supplied = ""
            if not secrets.compare_digest(supplied, access_code):
                self._unlock_page("Codice non corretto.", 403)
                return
            self.send_response(303)
            self.send_header("Location", "/")
            self.send_header("Set-Cookie",
                             f"employee_setup_token={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=3600")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            if not self._authorized():
                self._unlock_page()
                return
            path = urlsplit(self.path).path
            if path in ("/", "/index.html"):
                page = (PAGE.replace("__STEPS__", steps_json())
                            .replace("__TOKEN__", json.dumps(token))
                            .replace("__ENGINE__", json.dumps(config.get("engine", "postgresql")))
                            .replace("__DEMO__", json.dumps(bool(config.get("demo_data"))))
                            .replace("__VER__", WIZARD_VERSION)
                            .replace("__HOST__", f"{sysinfo.model} · {sysinfo.os_name}")
                            .replace("__PORT__", str(config.get("port", 8080)))
                            .replace("__JAR__", str(config.get("jar", "")))
                            .replace("__DATA__", str(config.get("data_dir", ""))))
                self._send(200, page.encode("utf-8"), "text/html; charset=utf-8")
            elif path == "/stream":
                self._stream()
            else:
                self._send(404, b"non trovato", "text/plain; charset=utf-8")

        def _stream(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            channel: "queue.Queue[str]" = queue.Queue(maxsize=2000)
            with _clients_lock:
                _clients.append(channel)
            try:
                while True:
                    try:
                        payload = channel.get(timeout=15)
                        self.wfile.write(f"data: {payload}\n\n".encode("utf-8"))
                    except queue.Empty:
                        # SSE comment as heartbeat: keeps the connection alive
                        # through proxies and routers that close idle sessions,
                        # and promptly detects when the browser is gone.
                        self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with _clients_lock:
                    if channel in _clients:
                        _clients.remove(channel)

        def do_POST(self):
            path = urlsplit(self.path).path
            if path == "/unlock" and token:
                self._unlock()
                return
            if not self._authorized():
                # Do not read an untrusted request body merely to discard it.
                # Close the HTTP/1.1 connection so unread bytes cannot become
                # the method prefix of the next request on the same socket.
                self.close_connection = True
                self._send(403, json.dumps({"error": "chiave temporanea non valida"}).encode(),
                           "application/json; charset=utf-8")
                return
            if path not in ("/start", "/test-smtp"):
                self._send(404, b"non trovato", "text/plain; charset=utf-8")
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length) or b"{}")
            except (ValueError, json.JSONDecodeError):
                self._send(400, json.dumps({"error": "richiesta non valida"}).encode(),
                           "application/json; charset=utf-8")
                return
            if path == "/test-smtp":
                try:
                    message = _smtp_test(payload)
                    body = {"ok": True, "message": message}
                    self._send(200, json.dumps(body).encode(), "application/json; charset=utf-8")
                except (ValueError, OSError, smtplib.SMTPException) as exc:
                    body = {"ok": False, "error": f"Test SMTP non riuscito: {exc}"}
                    self._send(400, json.dumps(body).encode(), "application/json; charset=utf-8")
                return
            # Claim the run here, not inside the thread: between responding to
            # the browser and starting the thread, a second request would
            # otherwise still find the wizard "free."
            if not _claim_run():
                self._send(409, json.dumps({"error": "installazione gia' in corso"}).encode(),
                           "application/json; charset=utf-8")
                return
            threading.Thread(target=worker, args=(payload,), daemon=True).start()
            self._send(200, json.dumps({"ok": True}).encode(), "application/json; charset=utf-8")

    # LAN mode is protected by an unguessable per-process token. It is still
    # plain HTTP, so it must only be used on a trusted local network and the
    # process must be stopped as soon as setup is complete.
    server = ThreadingHTTPServer((host, port), Handler)
    print("")
    print("=" * 54)
    if token:
        print("  Wizard temporaneo disponibile sulla rete locale.")
        setup_url = f"http://{ip}:{port}"
        display_code = f"{access_code[:4]}-{access_code[4:]}"
        print("  Apri dal PC:")
        print(f"      \033]8;;{setup_url}\033\\{setup_url}\033]8;;\033\\")
        print(f"  Codice di accesso: {display_code}")
        print("  Non condividere il codice: consente di modificare il sistema.")
    else:
        print("  Wizard in ascolto (solo su questa macchina).")
        print("  Dal TUO PC apri un tunnel e poi il browser:")
        print(f"      ssh -L {port}:localhost:{port} {os.environ.get('SUDO_USER', 'pi')}@{ip}")
        print(f"      http://localhost:{port}")
    print("=" * 54)
    print("  Il wizard si chiude automaticamente dopo l'installazione.")
    print("  Ctrl-C per chiuderlo prima.")
    print("")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        get_abort_event().set()
        print("\n  Chiuso.")
    finally:
        server.server_close()
    return 0 if _state.get("ok") else 1
