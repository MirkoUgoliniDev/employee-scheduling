#!/usr/bin/env python3
"""Diagnose the SMTP configuration used by Employee Scheduling."""

from __future__ import annotations

import argparse
import re
import shlex
import smtplib
import socket
import ssl
import sys
from email.message import EmailMessage
from pathlib import Path
from typing import NoReturn


DEFAULT_ENV_FILE = Path("/etc/employee-scheduling.env")
EMAIL_PATTERN = re.compile(r"^[^\s@]{1,64}@[^\s@]+\.[^\s@]{1,64}$")


def stage(name: str, message: str) -> None:
    print(f"[{name:<6}] {message}", flush=True)


def fail(message: str, hint: str | None = None) -> NoReturn:
    stage("FAILED", message)
    if hint:
        stage("HINT", hint)
    raise SystemExit(1)


def parse_environment(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except PermissionError:
        fail(
            f"Permission denied while reading {path}.",
            f"Run this command with sudo: sudo python3 {Path(__file__).name}",
        )
    except OSError as error:
        fail(f"Cannot read {path}: {error}")

    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"Invalid environment entry at {path}:{line_number}.")
        key, raw_value = line.split("=", 1)
        key = key.strip()
        try:
            parsed = shlex.split(raw_value, comments=False, posix=True)
        except ValueError as error:
            fail(f"Invalid quoting at {path}:{line_number}: {error}")
        if len(parsed) > 1:
            fail(f"Invalid unquoted whitespace at {path}:{line_number}.")
        values[key] = parsed[0] if parsed else ""
    return values


def require(values: dict[str, str], key: str) -> str:
    value = values.get(key, "").strip()
    if not value:
        fail(f"{key} is missing or empty in the environment file.")
    return value


def valid_email(value: str) -> bool:
    return EMAIL_PATTERN.fullmatch(value.strip()) is not None


def server_text(value: bytes | str) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Test the SMTP settings in /etc/employee-scheduling.env without exposing secrets."
    )
    parser.add_argument(
        "recipient",
        nargs="?",
        help="Test recipient address (defaults to QUARKUS_MAILER_FROM).",
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        default=DEFAULT_ENV_FILE,
        help=f"Environment file (default: {DEFAULT_ENV_FILE}).",
    )
    parser.add_argument("--timeout", type=float, default=15.0, help="Network timeout in seconds.")
    args = parser.parse_args()

    stage("CONFIG", f"Reading {args.env_file}")
    values = parse_environment(args.env_file)
    host = require(values, "QUARKUS_MAILER_HOST")
    username = require(values, "QUARKUS_MAILER_USERNAME")
    password = require(values, "QUARKUS_MAILER_PASSWORD")
    sender = require(values, "QUARKUS_MAILER_FROM")
    recipient = (args.recipient or sender).strip()
    tls_mode = values.get("QUARKUS_MAILER_START_TLS", "OPTIONAL").strip().upper()

    try:
        port = int(require(values, "QUARKUS_MAILER_PORT"))
    except ValueError:
        fail("QUARKUS_MAILER_PORT must be a number.")
    if not 1 <= port <= 65535:
        fail("QUARKUS_MAILER_PORT must be between 1 and 65535.")
    if not valid_email(sender):
        fail(f"QUARKUS_MAILER_FROM is not a valid email address: {sender}")
    if not valid_email(recipient):
        fail(f"The recipient is not a valid email address: {recipient}")

    stage("CONFIG", f"Server: {host}:{port}")
    stage("CONFIG", f"Username: {username}")
    stage("CONFIG", f"Password: configured ({len(password)} characters; value hidden)")
    stage("CONFIG", f"Sender: {sender}")
    stage("CONFIG", f"Recipient: {recipient}")
    stage("CONFIG", f"STARTTLS: {tls_mode}")

    try:
        addresses = sorted({item[4][0] for item in socket.getaddrinfo(host, port)})
    except socket.gaierror as error:
        fail(f"DNS lookup failed for {host}: {error}", "Check the SMTP host name and Raspberry network DNS.")
    stage("DNS", f"Resolved {host} to {', '.join(addresses)}")

    client: smtplib.SMTP | smtplib.SMTP_SSL | None = None
    try:
        context = ssl.create_default_context()
        if port == 465:
            stage("TCP", f"Connecting with implicit TLS to {host}:{port}...")
            client = smtplib.SMTP_SSL(host, port, timeout=args.timeout, context=context)
        else:
            stage("TCP", f"Connecting to {host}:{port}...")
            client = smtplib.SMTP(host, port, timeout=args.timeout)
        stage("TCP", f"Connected; server greeting: {server_text(client.noop()[1])}")

        code, response = client.ehlo()
        if code != 250:
            fail(f"EHLO failed with SMTP {code}: {server_text(response)}")
        features = ", ".join(sorted(client.esmtp_features)) or "none advertised"
        stage("EHLO", f"Server features: {features}")

        if port != 465:
            supports_starttls = client.has_extn("starttls")
            stage("TLS", f"STARTTLS advertised: {'yes' if supports_starttls else 'no'}")
            if tls_mode == "REQUIRED" and not supports_starttls:
                fail("STARTTLS is required, but the server did not advertise it.")
            if supports_starttls and tls_mode != "DISABLED":
                stage("TLS", "Starting encrypted TLS session...")
                client.starttls(context=context)
                client.ehlo()

        sock = client.sock
        if isinstance(sock, ssl.SSLSocket):
            cipher = sock.cipher()
            stage("TLS", f"Encrypted with {sock.version()} / {cipher[0] if cipher else 'unknown cipher'}")
        else:
            stage("TLS", "Connection is not encrypted")

        auth_methods = client.esmtp_features.get("auth", "not advertised")
        stage("AUTH", f"Server authentication methods: {auth_methods}")
        stage("AUTH", f"Authenticating as {username} (password hidden)...")
        client.login(username, password)
        stage("AUTH", "Authentication accepted")

        message = EmailMessage()
        message["From"] = sender
        message["To"] = recipient
        message["Subject"] = "Employee Scheduling SMTP test"
        message.set_content(
            "SMTP configuration test completed successfully.\n\n"
            "This message was generated by scripts/test-smtp.py."
        )
        stage("SEND", "Submitting the test message...")
        refused = client.send_message(message)
        if refused:
            fail(f"The server refused one or more recipients: {refused}")
        stage("SEND", "Message accepted by the SMTP server")
        stage("OK", "SMTP test completed successfully. Check the recipient inbox and spam folder.")
        return 0

    except smtplib.SMTPAuthenticationError as error:
        fail(
            f"Authentication rejected with SMTP {error.smtp_code}: {server_text(error.smtp_error)}",
            "Use the Brevo SMTP key as QUARKUS_MAILER_PASSWORD; do not use the Brevo API key.",
        )
    except smtplib.SMTPSenderRefused as error:
        fail(
            f"Sender rejected with SMTP {error.smtp_code}: {server_text(error.smtp_error)}",
            f"Verify that {sender} is an authorized sender in Brevo.",
        )
    except smtplib.SMTPRecipientsRefused as error:
        fail(f"Recipient rejected: {error.recipients}")
    except (smtplib.SMTPException, OSError, TimeoutError) as error:
        fail(f"SMTP test failed: {error}")
    finally:
        if client is not None:
            try:
                client.quit()
            except (smtplib.SMTPException, OSError):
                client.close()


if __name__ == "__main__":
    sys.exit(main())
