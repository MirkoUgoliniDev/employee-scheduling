import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Card, Col, Form, Modal, OverlayTrigger, Row, Spinner, Table, Tooltip } from 'react-bootstrap'
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import { faFloppyDisk, faPen, faCircleQuestion, faMicrochip, faCalendarWeek, faSliders, faMoon, faToggleOn } from '@fortawesome/free-solid-svg-icons'
import type { IconDefinition } from '@fortawesome/fontawesome-svg-core'
import toast from 'react-hot-toast'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router-dom'
import { structuresApi, type Structure } from '../../api/structures'
import { solverSettingsApi, type SolverSettings } from '../../api/solverSettings'
import './SolverSettingsSection.css'

type RowData = { structure: Structure; settings: SolverSettings }

/** Detailed explanations (Italian fallback) shown in the "?" tooltip beside each setting.
 *  Translations in all five languages are seeded as `solver.help.<field>` keys. */
const HELP_IT: Record<string, string> = {
  max_solve_seconds: "Tempo massimo di calcolo (5–600 s): oltre il limite viene restituita la miglior soluzione trovata.",
  unimproved_seconds: "Ferma il calcolo se non ci sono miglioramenti per questi secondi; 0 disattiva lo stop anticipato.",
  diminished_window_seconds: "Finestra (secondi) per lo stop a rendimento decrescente: confronta il ritmo di miglioramento con quello di N secondi prima; 0 disattiva.",
  diminished_ratio_pct: "Il solver si ferma quando il ritmo di miglioramento scende sotto questa percentuale del ritmo iniziale (1–100); attivo solo con finestra > 0.",
  context_days: "Giorni adiacenti alla finestra i cui turni già assegnati sono caricati come contesto bloccato (sovrapposizioni, riposo, ore e giorni consecutivi li vedono ma non li cambiano); 0 = solo finestra.",
  minimum_rest_hours: "Ore minime di riposo tra la fine di un turno e l'inizio del successivo dello stesso operatore (vincolo rigido).",
  max_shifts_per_day: "Numero massimo di turni per lo stesso operatore nello stesso giorno (1–5).",
  max_weekly_hours: "Tetto di ore settimanali per operatore; 0 disabilita il limite.",
  min_weekly_shifts: "Turni minimi settimanali per operatore; 0 disabilita il minimo.",
  max_weekly_shifts: "Turni massimi settimanali per operatore; 0 disabilita il massimo.",
  max_consecutive_days: "Giorni lavorativi consecutivi massimi per operatore; 0 disabilita il limite.",
  min_days_off_per_week: "Giorni di riposo minimi per operatore in una settimana (0–7).",
  desired_date_weight: "Quanto premiare i turni nelle date preferite dall'operatore; più alto = il solver le accontenta di più (0–10).",
  undesired_date_weight: "Quanto penalizzare i turni nelle date sgradite dall'operatore; più alto = il solver le evita di più (0–10).",
  balance_weight: "Quanto conta l'equità del carico tra operatori (per ore o per numero turni, vedi opzione dedicata); più alto = carichi più uniformi (0–10).",
  optional_skill_weight: "Quanto premiare gli operatori che hanno anche le competenze opzionali del turno, oltre a quelle obbligatorie (0–10).",
  same_location_weight: "Quanto premiare la continuità di sede: stessi operatori nella stessa sede in giorni vicini (0–10).",
  night_balance_weight: "Quanto conta l'equità nella distribuzione dei turni notturni (fascia definita da Ora inizio/fine notte) (0–10).",
  unassigned_weight: "Penalità per ogni turno lasciato scoperto; più alta = il solver copre più turni anche a scapito dei pesi soft (1–100).",
  avoid_specialist_weight: "Quanto penalizzare l'abbinamento di un operatore a uno specialista marcato 'da evitare' (0–10).",
  weekly_shift_weight: "Quanto conta il rispetto dei turni settimanali minimi/massimi impostati; 0 disattiva la penalità (0–10).",
  days_off_weight: "Quanto conta il rispetto dei riposi minimi settimanali impostati; 0 disattiva la penalità (0–10).",
  consecutive_days_weight: "Quanto conta il rispetto del massimo di giorni lavorativi consecutivi impostato; 0 disattiva la penalità (0–10).",
  night_start_hour: "Ora di inizio della fascia notturna (0–23), usata per identificare e bilanciare i turni notturni.",
  night_end_hour: "Ora di fine della fascia notturna (0–23), usata per identificare e bilanciare i turni notturni.",
  balance_by_hours: "Se attivo il bilanciamento considera le ore totali lavorate, altrimenti il numero di turni.",
  allow_unassigned: "Se attivo il solver può lasciare turni scoperti (con penalità) invece di forzare sempre la copertura.",
  stop_when_feasible: "Se attivo il solver si ferma appena tutti i vincoli rigidi sono rispettati, senza ottimizzare ulteriormente i pesi soft (più veloce).",
}

/** "?" icon with a detailed localized tooltip beside the setting name. */
function HelpIcon({ helpKey }: { helpKey: string }) {
  const { t } = useTranslation()
  const text = t('solver.help.' + helpKey, HELP_IT[helpKey] ?? '')
  if (!text) return null
  return (
    <OverlayTrigger placement="top" overlay={<Tooltip id={`help-${helpKey}`} className="solver-help-tooltip">{text}</Tooltip>}>
      <span className="ms-1 text-primary" style={{ cursor: 'help' }} role="button" tabIndex={0} aria-label={text}>
        <FontAwesomeIcon icon={faCircleQuestion} />
      </span>
    </OverlayTrigger>
  )
}
type NumberField = keyof Pick<SolverSettings,'max_solve_seconds'|'unimproved_seconds'|'diminished_window_seconds'|'diminished_ratio_pct'|'context_days'|'minimum_rest_hours'|'max_shifts_per_day'|'desired_date_weight'|'undesired_date_weight'|'balance_weight'|'optional_skill_weight'|'max_weekly_hours'|'min_weekly_shifts'|'max_weekly_shifts'|'max_consecutive_days'|'min_days_off_per_week'|'unassigned_weight'|'same_location_weight'|'night_balance_weight'|'night_start_hour'|'night_end_hour'|'avoid_specialist_weight'|'weekly_shift_weight'|'days_off_weight'|'consecutive_days_weight'>

const groups: { title:string; icon:IconDefinition; accent:string; fields:[NumberField,string,string,number,number][] }[] = [
  {title:'Elaborazione',icon:faMicrochip,accent:'#6f42c1',fields:[
    ['max_solve_seconds','Durata massima (secondi)','5–600 secondi.',5,600],
    ['unimproved_seconds','Stop senza miglioramenti','0 disabilita lo stop anticipato.',0,600],
    ['diminished_window_seconds','Stop a rendimento decrescente: finestra (s)','0 disabilita. Confronta il miglioramento attuale con quello di N secondi prima e si ferma quando il ritmo cala sotto la soglia.',0,600],
    ['diminished_ratio_pct','Stop a rendimento decrescente: soglia (%)','1–100: percentuale del ritmo di miglioramento iniziale sotto cui fermarsi. Usato solo con finestra > 0.',1,100],
    ['context_days','Giorni di contesto (bordi finestra)','0–7: turni già assegnati nei giorni adiacenti alla finestra, visti (bloccati) dai vincoli di sovrapposizione, riposo, ore settimanali e giorni consecutivi. 0 = solo finestra.',0,7],
  ]},{title:'Regole giornaliere e settimanali',icon:faCalendarWeek,accent:'#0d6efd',fields:[
    ['minimum_rest_hours','Riposo minimo (ore)','Ore minime tra due turni.',0,24],
    ['max_shifts_per_day','Turni massimi giornalieri','Da 1 a 5.',1,5],
    ['max_weekly_hours','Ore massime settimanali','0 disabilita il limite.',0,168],
    ['min_weekly_shifts','Turni minimi settimanali','0 disabilita il minimo.',0,21],
    ['max_weekly_shifts','Turni massimi settimanali','0 disabilita il massimo.',0,21],
    ['max_consecutive_days','Giorni consecutivi massimi','0 disabilita il limite.',0,31],
    ['min_days_off_per_week','Riposi minimi settimanali','Da 0 a 7.',0,7],
  ]},{title:'Pesi di ottimizzazione',icon:faSliders,accent:'#fd7e14',fields:[
    ['desired_date_weight','Date desiderate','0–10.',0,10],
    ['undesired_date_weight','Date indesiderate','0–10.',0,10],
    ['balance_weight','Bilanciamento carico','0–10.',0,10],
    ['optional_skill_weight','Competenze opzionali','0–10.',0,10],
    ['same_location_weight','Continuità nella sede','0–10.',0,10],
    ['night_balance_weight','Bilanciamento notturni','0–10.',0,10],
    ['unassigned_weight','Penalità turno non assegnato','1–100.',1,100],
    ['avoid_specialist_weight','Specialista da evitare','0–10.',0,10],
    ['weekly_shift_weight','Turni settimanali (min/max)','0–10. Peso del rispetto dei turni settimanali minimi/massimi.',0,10],
    ['days_off_weight','Riposi minimi settimanali','0–10. Peso del rispetto dei riposi minimi settimanali.',0,10],
    ['consecutive_days_weight','Giorni consecutivi massimi','0–10. Peso del rispetto del massimo di giorni consecutivi.',0,10],
  ]},{title:'Fascia notturna',icon:faMoon,accent:'#6610f2',fields:[
    ['night_start_hour','Ora inizio notte','0–23.',0,23],['night_end_hour','Ora fine notte','0–23.',0,23],
  ]},
]

/** [min,max] bounds for every numeric field, derived from groups: single source for clamping and validation. */
const FIELD_BOUNDS = Object.fromEntries(
  groups.flatMap(g => g.fields.map(([k, , , mn, mx]) => [k, [mn, mx]] as const))
) as Record<NumberField, [number, number]>

/** Effective maximum for a field given the current form: `unimproved_seconds` is dynamically
 *  limited to `max_solve_seconds` (as required by the backend); other fields use the static maximum. */
function effectiveMax(key: NumberField, form: SolverSettings): number {
  const max = FIELD_BOUNDS[key][1]
  if (key === 'unimproved_seconds') return Math.min(max, form.max_solve_seconds)
  return max
}

/** Returns the i18n key for the first cross-field error, or `null` when the form is valid.
 *  Mirrors the reciprocal rules in SolverSettingsResource.java that would otherwise yield an opaque 400. */
function crossFieldError(form: SolverSettings): string | null {
  if (form.unimproved_seconds > form.max_solve_seconds) return 'solver.err.unimproved'
  if (form.max_weekly_shifts > 0 && form.min_weekly_shifts > form.max_weekly_shifts) return 'solver.err.weekly'
  return null
}

type FocusField = NumberField | 'balance_by_hours' | 'allow_unassigned' | 'stop_when_feasible'

/** Timefold constraint name (asConstraint) → Solver Settings field that controls it.
 *  Fixed hard constraints without a setting (e.g. Overlapping shift) are omitted: only the modal opens. */
const CONSTRAINT_FIELD: Record<string, FocusField> = {
  'At least 10 hours between 2 shifts': 'minimum_rest_hours',
  'Max one shift per day': 'max_shifts_per_day',
  'Maximum weekly hours': 'max_weekly_hours',
  'Weekly shift range': 'min_weekly_shifts',
  'Minimum weekly shifts (empty week)': 'min_weekly_shifts',
  'Maximum consecutive days': 'max_consecutive_days',
  'Minimum days off per week': 'min_days_off_per_week',
  'Desired day for employee': 'desired_date_weight',
  'Undesired day for employee': 'undesired_date_weight',
  'Optional skill match': 'optional_skill_weight',
  'Same location continuity': 'same_location_weight',
  'Avoid specialist': 'avoid_specialist_weight',
  'Balance employee shift assignments': 'balance_weight',
  'Balance employee hours': 'balance_weight',
  'Balance night shifts': 'night_balance_weight',
  'Unassigned shift penalty': 'unassigned_weight',
  'Unassigned shift forbidden': 'allow_unassigned',
}

/** Group title (Italian) → i18n key suffix `solver.group.<key>`. */
const GROUP_KEY: Record<string, string> = {
  'Elaborazione': 'processing',
  'Regole giornaliere e settimanali': 'dailyWeekly',
  'Pesi di ottimizzazione': 'weights',
  'Fascia notturna': 'night',
}

/** Highlight style for the field reached by clicking a constraint. */
const FOCUS_STYLE: React.CSSProperties = { outline: '3px solid #dc3545', outlineOffset: 4, borderRadius: 4 }

function SettingsModal({row,focusField,onClose,onSaved}:{row:RowData|null;focusField?:FocusField|null;onClose:()=>void;onSaved:()=>void}){
  const {t}=useTranslation()
  const [form,setForm]=useState<SolverSettings|null>(null);const [saving,setSaving]=useState(false)
  const focusRef=useRef<HTMLDivElement|null>(null)
  useEffect(()=>setForm(row?{...row.settings}:null),[row])
  // Scroll to the clicked constraint's field as soon as the modal is rendered.
  useEffect(()=>{
    if(!row||!focusField)return
    const timer=setTimeout(()=>focusRef.current?.scrollIntoView({behavior:'smooth',block:'center'}),250)
    return()=>clearTimeout(timer)
  },[row,focusField])
  if(!row||!form)return null
  const number=(key:NumberField)=>(e:React.ChangeEvent<HTMLInputElement>)=>setForm(f=>f&&({...f,[key]:Number(e.target.value)}))
  // On blur, clamp the value to [min, effectiveMax], preventing out-of-range values from reaching the backend.
  const clamp=(key:NumberField)=>()=>setForm(f=>{if(!f)return f;const mn=FIELD_BOUNDS[key][0];const mx=effectiveMax(key,f);const v=Number(f[key]);return{...f,[key]:isNaN(v)?mn:Math.min(Math.max(v,mn),mx)}})
  const bool=(key:'balance_by_hours'|'allow_unassigned'|'stop_when_feasible')=>(e:React.ChangeEvent<HTMLInputElement>)=>setForm(f=>f&&({...f,[key]:e.target.checked}))
  const err=crossFieldError(form)
  async function save(){if(crossFieldError(form!))return;setSaving(true);try{await solverSettingsApi.save(row!.structure.id,form!);toast.success(t('toast.solverSettingsSaved','Parametri Solver salvati.'));onSaved();onClose()}catch{toast.error(t('toast.errorSave','Errore durante il salvataggio.'))}finally{setSaving(false)}}
  return <Modal show onHide={onClose} size="xl" centered scrollable><Modal.Header closeButton><Modal.Title>{t('config.menu.solverSettings','Parametri Solver')} — {row.structure.name}</Modal.Title></Modal.Header><Modal.Body>
    {groups.map(group=><Card className="mb-3 shadow-sm" key={group.title}><Card.Header className="bg-light d-flex align-items-center gap-2 fw-semibold"><FontAwesomeIcon icon={group.icon} style={{color:group.accent}}/>{t('solver.group.'+(GROUP_KEY[group.title]??''), group.title)}</Card.Header><Card.Body><Row className="g-3">{group.fields.map(([key,label,hint,min])=><Col md={6} lg={4} key={key}><Form.Group ref={key===focusField?focusRef:undefined} style={key===focusField?FOCUS_STYLE:undefined}><Form.Label className="fw-semibold">{t('solver.label.'+key, label)}<HelpIcon helpKey={key}/></Form.Label><Form.Control type="number" min={min} max={effectiveMax(key,form)} value={form[key] as number} onChange={number(key)} onBlur={clamp(key)} isInvalid={(key==='unimproved_seconds'&&err==='solver.err.unimproved')||((key==='min_weekly_shifts'||key==='max_weekly_shifts')&&err==='solver.err.weekly')}/><Form.Text>{t('solver.hint.'+key, hint)}</Form.Text></Form.Group></Col>)}</Row></Card.Body></Card>)}
    <Card className="mb-2 shadow-sm"><Card.Header className="bg-light d-flex align-items-center gap-2 fw-semibold"><FontAwesomeIcon icon={faToggleOn} style={{color:'#198754'}}/>{t('solver.group.options','Opzioni')}</Card.Header><Card.Body><Row className="g-3"><Col md={4}><div className="d-inline-flex align-items-center gap-1" ref={focusField==='balance_by_hours'?focusRef:undefined} style={focusField==='balance_by_hours'?FOCUS_STYLE:undefined}><Form.Check type="switch" label={t('solver.opt.balance_by_hours','Bilancia il carico per ore (anziché per numero turni)')} checked={form.balance_by_hours} onChange={bool('balance_by_hours')}/><HelpIcon helpKey="balance_by_hours"/></div></Col><Col md={4}><div className="d-inline-flex align-items-center gap-1" ref={focusField==='allow_unassigned'?focusRef:undefined} style={focusField==='allow_unassigned'?FOCUS_STYLE:undefined}><Form.Check type="switch" label={t('solver.opt.allow_unassigned','Consenti turni non assegnati')} checked={form.allow_unassigned} onChange={bool('allow_unassigned')}/><HelpIcon helpKey="allow_unassigned"/></div></Col><Col md={4}><div className="d-inline-flex align-items-center gap-1" ref={focusField==='stop_when_feasible'?focusRef:undefined} style={focusField==='stop_when_feasible'?FOCUS_STYLE:undefined}><Form.Check type="switch" label={t('solver.opt.stop_when_feasible','Interrompi alla prima soluzione fattibile')} checked={form.stop_when_feasible} onChange={bool('stop_when_feasible')}/><HelpIcon helpKey="stop_when_feasible"/></div></Col></Row></Card.Body></Card>
  </Modal.Body><Modal.Footer>{err&&<span className="text-danger small me-auto">{err==='solver.err.unimproved'?t('solver.err.unimproved','Lo stop senza miglioramenti non può superare la durata massima.'):t('solver.err.weekly','I turni minimi settimanali non possono superare i massimi.')}</span>}<Button variant="secondary" onClick={onClose}>{t('btn.cancel','Annulla')}</Button><Button onClick={save} disabled={saving||!!err}>{saving?<Spinner size="sm"/>:<><FontAwesomeIcon icon={faFloppyDisk} className="me-1"/>{t('btn.save','Salva')}</>}</Button></Modal.Footer></Modal>
}

export default function SolverSettingsSection(){
  const {t}=useTranslation();const [rows,setRows]=useState<RowData[]>([]);const [loading,setLoading]=useState(true);const [editing,setEditing]=useState<RowData|null>(null)
  const [focusField,setFocusField]=useState<FocusField|null>(null)
  const [searchParams,setSearchParams]=useSearchParams()
  const load=useCallback(async()=>{setLoading(true);try{const structures=await structuresApi.list();const settings=await Promise.all(structures.map(s=>solverSettingsApi.get(s.id)));setRows(structures.map((s,i)=>({structure:s,settings:settings[i]})))}catch{toast.error(t('toast.errorLoad','Errore nel caricamento.'))}finally{setLoading(false)}},[t])
  useEffect(()=>{load()},[load])
  // Deep link from Solve Result: ?structureId= opens the company modal, ?constraint= highlights the field.
  useEffect(()=>{
    if(loading||rows.length===0)return
    const sid=Number(searchParams.get('structureId'));if(!sid)return
    const row=rows.find(r=>r.structure.id===sid);if(!row)return
    const constraint=searchParams.get('constraint')
    setFocusField(constraint?CONSTRAINT_FIELD[constraint]??null:null)
    setEditing(row)
    // Clean the URL so closing/reopening the section does not reopen the modal.
    setSearchParams(prev=>{const q=new URLSearchParams(prev);q.delete('structureId');q.delete('constraint');return q},{replace:true})
  },[loading,rows,searchParams,setSearchParams])
  if(loading)return <div className="text-center py-5"><Spinner/></div>
  return <div><h5 className="mb-3">{t('config.menu.solverSettings','Parametri Solver')}</h5><Table bordered hover responsive size="sm" className="align-middle" style={{maxWidth:1100}}><thead className="table-dark"><tr><th style={{width:55}}>ID</th><th>{t('col.company','Azienda')}</th><th>{t('solver.col.duration','Durata')}</th><th>{t('solver.col.minRest','Riposo minimo')}</th><th>{t('solver.col.maxPerDay','Max turni/giorno')}</th><th>{t('solver.col.balance','Bilanciamento')}</th><th style={{width:90}}>{t('col.actions','Azioni')}</th></tr></thead><tbody>{rows.map(row=><tr key={row.structure.id}><td className="text-muted">{row.structure.id}</td><td className="fw-semibold">{row.structure.name}</td><td>{row.settings.max_solve_seconds}s</td><td>{row.settings.minimum_rest_hours}h</td><td>{row.settings.max_shifts_per_day}</td><td>{row.settings.balance_by_hours?t('solver.balance.hours','Ore'):t('solver.balance.shifts','Turni')}</td><td><Button variant="link" size="sm" className="p-0" title={t('btn.edit','Modifica')} onClick={()=>{setFocusField(null);setEditing(row)}}><FontAwesomeIcon icon={faPen}/></Button></td></tr>)}</tbody></Table><SettingsModal row={editing} focusField={focusField} onClose={()=>{setEditing(null);setFocusField(null)}} onSaved={load}/></div>
}
