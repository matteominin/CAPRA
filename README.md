# SWE-Audit-Agent

A multi-agent LLM system that automatically audits Software Engineering PDF documents (theses, project reports) and produces a detailed LaTeX/PDF audit report.

Built with **Java 25**, **Spring Boot 4.0.3**, **Spring AI 2.0.0-M2**, **OpenAI GPT-5.1**, and **Anthropic Haiku 4.5**.

---

## Architecture Overview

The system implements a **7-step pipeline** orchestrated by `MultiAgentOrchestrator`. Five specialized agents analyze the document in parallel, followed by evidence verification, confidence filtering, cross-verification, LaTeX report generation, and PDF compilation.

```
                         ┌─────────────────────┐
                         │   PDF Upload (REST)  │
                         └──────────┬──────────┘
                                    │
                         ┌──────────▼──────────┐
                    [1]  │  Flask PDF Extractor │  (Python + OpenAI Vision)
                         └──────────┬──────────┘
                                    │ full text
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
     ┌────────▼────────┐  ┌────────▼────────┐  ┌────────▼────────┐
[2]  │ RequirementsAgent│  │ TestAuditorAgent│  │FeatureCheckAgent│
     │   (GPT-5.1)     │  │   (GPT-5.1)     │  │   (GPT-5.1)     │
     └────────┬────────┘  └────────┬────────┘  └────────┬────────┘
              │                     │                     │
              │  ┌──────────────────┼──────────────────┐  │
              │  │                  │                   │  │
              │  │  ┌───────────────▼──────────────┐   │  │
              │  │  │ TraceabilityMatrixAgent       │   │  │
              │  │  │   (GPT-5.1)                  │   │  │
              │  │  └───────────────┬──────────────┘   │  │
              │  │                  │                   │  │
              │  │  ┌───────────────▼──────────────┐   │  │
              │  │  │ GlossaryConsistencyAgent      │   │  │
              │  │  │   (GPT-5.1)                  │   │  │
              │  │  └───────────────┬──────────────┘   │  │
              │  │                  │                   │  │
     ┌────────▼──▼──────────────────▼──────────────────▼──▼────────┐
[3]  │              Evidence Anchoring Service                      │
     │      (Fuzzy string matching — NO LLM, pure algorithms)      │
     └────────────────────────────┬────────────────────────────────┘
                                  │
     ┌────────────────────────────▼────────────────────────────────┐
[4]  │              Confidence Filtering (threshold ≥ 0.65)        │
     └────────────────────────────┬────────────────────────────────┘
                                  │
     ┌────────────────────────────▼────────────────────────────────┐
[5]  │              ConsistencyManager (GPT-5.1)                   │
     │    Cross-verification + deduplication + renumbering          │
     └────────────────────────────┬────────────────────────────────┘
                                  │
     ┌────────────────────────────▼────────────────────────────────┐
[6]  │              LaTeX Report Service (Haiku 4.5 + fallback)    │
     └────────────────────────────┬────────────────────────────────┘
                                  │
     ┌────────────────────────────▼────────────────────────────────┐
[7]  │              pdflatex Compilation (2-pass)                   │
     └─────────────────────────────────────────────────────────────┘
```

---

## Step-by-Step Pipeline

### Step 1 — Text Extraction (`DocumentIngestionService`)

The uploaded PDF is sent to an external **Flask microservice** (running on port 5001) that uses **PyMuPDF** for text extraction and **OpenAI Vision** for image descriptions. The service returns the complete textual representation of the document, including descriptions of diagrams and figures.

- **Timeout**: 5 minutes read, 30 seconds connect
- **Endpoint**: `POST /extract` with `mode=full`

### Step 2 — Parallel Agent Analysis

Five agents run **concurrently** using Java virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`). Each agent receives the full document text and uses **GPT-5.1** (temperature=0.0, seed=42) for deterministic analysis.

#### 2a. RequirementsAgent

**Purpose**: Identifies issues in requirements, use cases, and business logic.

**What it checks**:
- Completeness of each requirement (pre/post-conditions, alternative flows)
- Ambiguities, contradictions, and missing requirements
- Consistency between functional and non-functional requirements
- Use cases without corresponding tests
- Coherence between diagrams and textual descriptions

**Output**: List of `AuditIssue` records with fields: `id` (REQ-001 format), `severity` (HIGH/MEDIUM/LOW), `description`, `pageReference`, `quote` (verbatim from document), `category`, `recommendation`, `confidenceScore`.

**Anti-hallucination rules**: The agent is instructed to copy quotes **verbatim** from the document. It must not paraphrase, and must not report issues with confidence below 0.7. Each recommendation must be concrete and actionable, not generic.

#### 2b. TestAuditorAgent

**Purpose**: Audits the test suite and design consistency.

**What it checks**:
- Critical requirements without corresponding test cases (coverage gaps)
- Inconsistencies between declared design principles (e.g. SOLID) and actual class descriptions
- Missing tests for alternative flows and error conditions
- Requirements-to-test traceability

**Output**: List of `AuditIssue` records (TST-001 format).

**Deduplication**: Instructed not to report issues already covered by RequirementsAgent. Focuses exclusively on testing gaps and architectural consistency.

#### 2c. FeatureCheckAgent

**Purpose**: Verifies the presence of **expected features** loaded from MongoDB.

**How it works**:
1. Loads feature definitions from the `summary_features` collection (MongoDB). Each feature has a name, description, and a checklist of criteria.
2. Sends the document + feature checklist to the LLM.
3. For each feature, the LLM evaluates which checklist items are satisfied and returns:
   - `status`: PRESENT (≥80% checklist), PARTIAL (30-79%), ABSENT (<30%)
   - `coverageScore`: percentage of satisfied items
   - `matchedItems` / `totalItems`
   - `evidence`: brief explanation of what was found or missing

**Output**: List of `FeatureCoverage` records.

#### 2d. TraceabilityMatrixAgent

**Purpose**: Builds a **traceability matrix** mapping Requirements/UC → Design → Tests.

**What it produces**: For each Use Case or Functional Requirement found in the document:
- Whether a related design/architecture description exists (`hasDesign`)
- Whether a related test case exists (`hasTest`)
- Brief references to the design and test artifacts
- Gap description if anything is missing

**Output**: List of `TraceabilityEntry` records.

This is one of the most valuable checks — students rarely produce a complete traceability matrix.

#### 2e. GlossaryConsistencyAgent

**Purpose**: Detects **terminological inconsistencies** in the document.

**What it checks**:
- Involuntary synonyms (same entity called by different names)
- Undefined technical terms
- Inconsistent acronyms
- Mixed Italian/English usage for the same concept

**Output**: List of `GlossaryIssue` records with `termGroup`, `variants`, `severity` (MAJOR/MINOR), and `suggestion`.

---

### Step 3 — Evidence Anchoring (`EvidenceAnchoringService`)

This is the **most effective anti-hallucination measure**. It is a purely algorithmic step (NO LLM involved).

For each issue produced by RequirementsAgent and TestAuditorAgent, the service verifies that the `quote` field actually exists in the document using **fuzzy string matching**:

1. **Exact match**: checks if the normalized quote is a substring of the document.
2. **Trigram overlap pre-filter**: quickly discards dissimilar windows.
3. **Sliding window + normalized Levenshtein distance**: finds the best-matching passage.
4. **LCS ratio fallback**: for very long strings, uses word-level Longest Common Subsequence.

**Decision logic**:
- **Similarity < 0.45** → issue is **discarded** (quote was likely hallucinated)
- **Similarity 0.45–0.70** → issue is kept but confidence is **penalized** proportionally
- **Similarity ≥ 0.70** → confidence is **boosted** (up to +15%)
- **No quote provided** → confidence is halved (50% penalty)
- **Quote too short (<15 chars)** → confidence reduced by 30%

### Step 4 — Confidence Filtering

Issues with `confidenceScore < 0.65` are discarded. This threshold works in conjunction with the agent-level floor of 0.7 and the Evidence Anchoring adjustments to produce a highly reliable final set.

### Step 5 — Cross-Verification (`ConsistencyManager`)

A **meta-agent** (GPT-5.1) receives the full document text and the surviving issues. For each issue, it:

1. **Searches** for the quoted text in the original document
2. **Verifies** the quote exists (even with minor formatting variations)
3. **Validates** that the page reference is plausible
4. **Evaluates** whether the issue describes a real problem or is a false positive
5. **Detects duplicates**: if two issues describe the same problem from different perspectives, only the most complete one is confirmed

**Post-processing**:
- Verified issues are **renumbered** sequentially by category prefix: `REQ-001`, `TST-001`, `ARCH-001`, `ISS-001`
- Deterministic ordering: category → severity ordinal → page reference → description

### Step 6 — LaTeX Report Generation (`LatexReportService`)

Uses **Anthropic Haiku 4.5** (temperature=0.0, max-tokens=8192) with fallback to GPT-5.1 for generating narrative LaTeX sections.

**Report sections**:

| # | Section | Generation Method |
|---|---------|-------------------|
| 1 | Document Context | LLM-generated (extracts project objective, use cases, requirements, architecture, testing strategy) |
| 2 | Executive Summary | LLM-generated (problem patterns, critical areas, overall quality assessment, top 3 priority actions) |
| 3 | Strengths | LLM-generated (3-6 specific positive aspects with evidence) |
| 4 | Feature Coverage | Programmatic table from `FeatureCoverage` data (status, coverage %, evidence) |
| 5 | Summary Table | Programmatic Category × Severity cross-tabulation |
| 6 | Issue Detail | Programmatic per-category, per-UC grouping with confidence badges and cross-references |
| 7 | Priority Recommendations | Programmatic — HIGH-severity issues only, one line each |
| 8 | Traceability Matrix | Programmatic table with ✓/✗ indicators and gap descriptions |
| 9 | Terminological Consistency | Programmatic table of glossary issues with severity and suggestions |

**LLM output sanitization** (`sanitizeLlmLatex`): Strips dangerous commands (`\documentclass`, `\usepackage`, `\begin{document}`, `\input`, `\include`, markdown code fences) from LLM-generated LaTeX to prevent template breakage.

**LaTeX escaping**: Two modes — `escapeLatex()` collapses newlines (for use inside `\textbf{}` etc.) and `escapeLatexBlock()` preserves paragraphs (for standalone text). All special characters (`& % $ # _ { } ~ ^ \`) are properly escaped.

### Step 7 — PDF Compilation (`LatexCompilerService`)

Compiles the `.tex` file to PDF using **pdflatex**:
- **Two passes**: first generates the `.toc`, second includes it
- **Timeout**: 120 seconds per pass
- **Mode**: `-interaction=nonstopmode -halt-on-error`
- Verifies pdflatex availability before compilation
- If compilation fails, the `.tex` file is still returned to the user

---

## Resilience Features

### ResilientLlmCaller

All LLM calls go through `ResilientLlmCaller.callEntity()` which provides:
- **Lenient JSON parsing**: tolerates trailing commas, Java-style comments, single quotes, unquoted field names
- **Automatic retry**: up to 3 attempts with exponential backoff (2s, 4s)
- **Structured output**: uses Spring AI's `BeanOutputConverter` to enforce typed JSON responses

### Determinism

- All LLM calls use `temperature=0.0` and `seed=42`
- Issues are sorted deterministically before any processing step
- ID renumbering follows a stable comparator: category → severity → page → description

---

## REST API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/analyze` | Upload PDF → returns compiled PDF report (or `.tex` fallback) |
| `POST` | `/api/analyze/json` | Upload PDF → returns JSON audit report |
| `GET`  | `/api/health` | Health check (verifies Flask extractor availability) |

---

## Configuration (`application.yaml`)

```yaml
audit:
  pdf-service:
    base-url: http://localhost:5001
  latex:
    output-dir: ./reports
    pdflatex-path: pdflatex

spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-5.1
          temperature: 0.0
          seed: 42
          max-tokens: 16000
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-haiku-4-5-20250401
          temperature: 0.0
          max-tokens: 8192
  data:
    mongodb:
      uri: mongodb://localhost:27017/features_repo

server:
  port: 8085
```

---

## Prerequisites

- **Java 25**
- **MongoDB** running on `localhost:27017` with a `features_repo` database containing a `summary_features` collection
- **Flask PDF extractor** running on port 5001
- **pdflatex** installed (TeX Live or MacTeX)
- **API keys**: `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` environment variables

## Running

```bash
./mvnw spring-boot:run
```

Then upload a PDF:
```bash
curl -X POST http://localhost:8085/api/analyze \
  -F "file=@document.pdf" \
  -o audit-report.pdf
```
