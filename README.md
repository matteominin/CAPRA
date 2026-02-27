# SWE-Audit-Agent

Sistema multi-agent che analizza documenti PDF di progetti di Ingegneria del Software e genera un report strutturato (`.tex` + `.pdf`).

Stack principale:
- Java 25
- Spring Boot 4.0.3
- Spring AI 2.0.0-M2
- OpenAI GPT-5.1 (analisi)
- Anthropic Haiku 4.5 (sezioni narrative del report)

---

## Pipeline reale (codice attuale)

La pipeline è orchestrata da `MultiAgentOrchestrator` ed è composta da 9 step:

1. **Text extraction** (`DocumentIngestionService`) via servizio Flask (`/extract`, mode=full)
2. **Analisi parallela** (6 agenti + 2 extractor):
   - `RequirementsAndUseCaseAgent`
   - `TestAuditorAgent`
   - `ArchitectureAgent`
   - `FeatureCheckAgent`
   - `UseCaseExtractorAgent`
   - `RequirementExtractorAgent`
3. **Traceability mapping** (`TraceabilityMatrixAgent`) con UCs/Requirements estratti
4. **Evidence Anchoring** (`EvidenceAnchoringService`, non-LLM)
5. **Confidence filtering** (`confidence >= 0.75`)
6. **Cross-verification LLM** (`ConsistencyManager`)
7. **Normalizzazione report** (`ReportNormalizer`)
8. **Generazione LaTeX** (`LatexReportService`)
9. **Compilazione PDF** (`LatexCompilerService`, `pdflatex`)

Parametri globali rilevanti:
- `CONFIDENCE_THRESHOLD = 0.75` (filtro finale issue nel pipeline orchestrator)
- ordinamento deterministico issue: `category -> severity.ordinal -> pageReference -> description`
- esecuzione parallela agenti tramite virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`)

---

## Agente per agente

### 1) `RequirementsAndUseCaseAgent`
- **Obiettivo**: trovare issue su requisiti/UC/business logic.
- **Prompt (sintesi tecnica)**:
  - calibrazione "academic-lenient": penalizza falsi positivi da over-auditing
  - richiede `quote` verbatim e `confidenceScore >= 0.8`
  - richiede grouping sistemico (evitare issue duplicati su UC diversi)
  - target qualitativo nel prompt: max ~5-6 issue.
- **Output**: `IssuesResponse` (`AuditIssue` con id `REQ-*`).
- **Post-processing locale**: nessuno dentro l’agente (solo chiamata `ResilientLlmCaller`).

### 2) `TestAuditorAgent`
- **Obiettivo**: audit testing/coverage/traceability req→test.
- **Prompt (sintesi tecnica)**:
  - stessa calibrazione accademica conservativa
  - evita finding su practice enterprise (load/mutation/CI/CD)
  - impone `quote` verbatim e `confidenceScore >= 0.8`
  - dedup rispetto a issue di requirements già noti.
- **Output**: `IssuesResponse` (`AuditIssue` con id `TST-*`).
- **Post-processing locale**: nessuno.

### 3) `ArchitectureAgent`
- **Obiettivo**: individuare problemi architetturali importanti.
- **Prompt (sintesi tecnica)**:
  - verifica coerenza descrizione architettura vs implementazione
  - evita segnalazioni su gap non rilevanti per contesto universitario
  - impone `quote` verbatim e `confidenceScore >= 0.8`
  - target qualitativo nel prompt: max ~3-4 issue.
- **Output**: `IssuesResponse` (`AuditIssue` con id `ARCH-*`).
- **Post-processing locale**: nessuno.

### 4) `FeatureCheckAgent`
- **Obiettivo**: verificare checklist feature attese da MongoDB (`summary_features`).
- **Prompt (sintesi tecnica)**:
  - per ogni feature calcola `status`, `coverageScore`, `matchedItems`, `totalItems`, `evidence`
  - soglie hard-coded nel prompt:
    - `PRESENT` se checklist soddisfatta >= 80%
    - `PARTIAL` se tra 30% e 79%
    - `ABSENT` se < 30%
  - output in EN, no traduzione di identificatori del documento.
- **Output**: `FeatureCoverageResponse`.
- **Post-processing locale**: logging distribuzione present/partial/absent.
- **Normalizzazione robustezza parsing**:
  - `FeatureCoverage` ha deserializer custom (`FeatureCoverageDeserializer`)
  - tollera JSON LLM con chiavi duplicate (`matchedItems`, `totalItems`) senza mandare in errore la pipeline.

### 5) `UseCaseExtractorAgent`
- **Obiettivo**: estrarre **tutti** gli UC dal documento (diagrammi, template, testo).
- **Prompt (sintesi tecnica)**:
  - exhaustive extraction (diagrammi + template + testo + appendici, include sub-UC)
  - `hasTemplate=true` solo se ci sono campi strutturati (actor/preconditions/main flow/...)
  - dedup richiesto a livello LLM (diagramma+template -> una sola entry).
- **Output**: `UseCaseExtractorResponse` (`List<UseCaseEntry>`).
- **Post-processing locale**: nessuno.

### 6) `RequirementExtractorAgent`
- **Obiettivo**: estrarre requisiti funzionali (espliciti o inferiti).
- **Prompt (sintesi tecnica)**:
  - preferisci ID espliciti (`RF-1`, `REQ-01`, ...)
  - se assenti, inferisci `REQ-1`, `REQ-2`, ...
  - mai usare `UC-*` come requirement ID
  - nome requisito corto (3-5 parole), user-facing
  - target nel prompt: 8-15 requisiti se evidenza sufficiente.
- **Output**: `RequirementExtractorResponse` (`List<RequirementEntry>`).
- **Normalizzazioni locali**:
  1. filtro euristico requisiti troppo tecnici (`session`, `validazione/validation`, `regex`, `dao`, `jdbc`, `database connection`, `persistence`)
  2. `normalizeRequirementIds(...)`:
     - dedup ID
     - progressione numerica senza collisioni
     - correzione ID invalidi/duplicati in `REQ-N`
     - regex ID validi: `(?i)^([A-Z]+-?)(\\d+)$`.

### 7) `TraceabilityMatrixAgent`
- **Obiettivo**: costruire matrice `Requirement -> UC -> Design -> Test`.
- **Prompt (sintesi tecnica)**:
  - usa le liste UC/Requirements già estratte
  - una riga per UC; righe specifiche per requirements senza UC.
- **Output**: `TraceabilityMatrixResponse` (`List<TraceabilityEntry>`).
- **Post-processing locale**: nessuno.

### 8) `ConsistencyManager` (meta-agent)
- **Obiettivo**: verificare issue candidati e ridurre falsi positivi/duplicati.
- **Prompt (sintesi tecnica)**:
  - verifica quote nel testo originale
  - corregge se necessario, merge issue duplicate/simili
  - approccio conservativo (meglio false negative che false positive).
- **Output**: `VerificationResponse` (`List<VerifiedIssue>`).
- **Post-processing locale**:
  - tiene solo `verified=true`
  - `renumber(...)` deterministico per categoria:
    - `REQ-*`, `TST-*`, `ARCH-*`, fallback `ISS-*`.
  - formato finale ID: `%s-%03d`.

---

## Normalizzazioni e post-processing globali

### `ResilientLlmCaller`
Tutte le chiamate LLM passano da qui:
- parsing JSON lenient (trailing comma, single quotes, ecc.)
- retry automatico (max 3 tentativi totali: tentativo iniziale + 2 retry)
- backoff lineare implementato: 2000ms, 4000ms
- serializzazione/parse typed con `BeanOutputConverter`.

### `EvidenceAnchoringService` (non-LLM)
- verifica quote con fuzzy match sul documento (normalizzazione testo + matching approssimato)
- soglie:
  - `MIN_SIMILARITY = 0.45` (issue sotto soglia scartata)
  - `BOOST_THRESHOLD = 0.70`
- regole confidence:
  - quote assente: `confidence * 0.5`
  - quote troppo corta (`<15` char normalizzati): `confidence * 0.7`
  - match in [0.45, 0.70): penalità proporzionale fino al 30%
  - match >= 0.70: boost proporzionale (max clamp a 1.0)
- matching strategy:
  1. exact match
  2. trigram overlap prefilter
  3. sliding window + normalized Levenshtein
  4. fallback LCS ratio su testi lunghi.

### `ReportNormalizer`
- assicura `shortDescription`
- penalizza issue senza quote
- ordina per confidence/severity
- limita issue a `MAX_ISSUES = 7` (hard cap)
- tronca `shortDescription` a `MAX_SHORT_DESC_LENGTH = 120`
- produce metriche di completeness
- estrae solo feature `PARTIAL/ABSENT` per sezione missing
- warning log-only su keyword critiche senza HIGH issue (non forza escalation severità).

### Regole aggiuntive di rendering in `LatexReportService`
- dedup UC template vs non-template (ID + chiave semantica nome)
- separazione UC diagrammatici (`summary/crud/diagram*`)
- esclusione UC diagrammatici dai link operativi requirement→UC in Requirements/Traceability
- dedup righe traceability e statistiche calcolate su UC normalizzati unici.

---

## Criteri quantitativi di inclusione/esclusione issue

I candidati issue passano queste fasi:
1. output agenti (prompt richiede tipicamente `confidenceScore >= 0.8`)
2. `EvidenceAnchoringService` (scarto su similarità quote)
3. filtro orchestrator: `confidence >= 0.75`
4. `ConsistencyManager` (`verified=true`)
5. `ReportNormalizer` (cap massimo 7 issue).

Questo schema privilegia precisione (meno false positive) rispetto al recall.

---

## Report LaTeX generato

`LatexReportService` produce queste sezioni:
1. Project Overview (narrativo LLM)
2. Requirements Analysis (tabella requisiti + UC link)
3. Architecture Analysis
4. Use Case Analysis
5. Testing Analysis
6. Traceability Analysis
7. Missing Features

Il servizio usa Haiku 4.5 come primaria e GPT-5.1 come fallback per sezioni narrative.

Dettagli tecnici di generazione:
- output `.tex` scritto in `./reports/<timestamp>/audit-report.tex`
- compilazione PDF in due passaggi (`pdflatex`)
- in caso di errore di compilazione viene comunque restituito il `.tex`.

---

## API REST

| Method | Endpoint | Descrizione |
|---|---|---|
| `POST` | `/api/analyze` | Carica PDF e restituisce report PDF (fallback `.tex`) |
| `POST` | `/api/analyze/json` | Carica PDF e restituisce `AuditReport` JSON |
| `GET` | `/api/health` | Health check servizio extractor Flask |

---

## Configurazione principale (`application.yaml`)

```yaml
spring:
  ai:
    openai:
      chat:
        options:
          model: gpt-5.1
          max-completion-tokens: 16000
          temperature: 0.0
          seed: 42
    anthropic:
      chat:
        options:
          model: claude-haiku-4-5-20251001
          max-tokens: 8192
          temperature: 0.0

audit:
  pdf-service:
    base-url: http://localhost:5001
  latex:
    output-dir: ./reports
    pdflatex-path: pdflatex
```

Dettagli runtime importanti:
- `server.port = 8085`
- `audit.pdf-service.base-url = http://localhost:5001`
- `DocumentIngestionService` timeout: connect 30s, read 5m
- `multipart` max size: 50MB.

---

## Prerequisiti

- Java 25
- MongoDB locale (`features_repo`, collection `summary_features`)
- servizio Flask extractor su porta 5001
- `pdflatex` installato
- variabili ambiente: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`

---

## Avvio

```bash
./mvnw spring-boot:run
```

Esempio chiamata:

```bash
curl -X POST http://localhost:8085/api/analyze \
  -F "file=@document.pdf" \
  -o audit-report.pdf
```
