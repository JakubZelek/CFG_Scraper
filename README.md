# CFGScraper

A distributed system for extracting Control Flow Graphs (CFGs) from source code repositories and storing them in Elasticsearch with isomorphism detection.

# Architecture
<img width="1086" height="441" alt="image" src="https://github.com/user-attachments/assets/4a8f3711-d634-47bf-a63f-bf4048d4ae2e" />


## Architecture Components

- **API (FastAPI)** - Receives requests from users and routes them to appropriate Kafka topics based on the language and settings
- **Language Topics** - Separate Kafka topic for each supported language
- **Language Scrapers** - Language-specific processors that clone repositories and generate CFGs
- **CFG Topic** - Kafka topic for processed CFG data
- **Elasticsearch Uploader** - Consumes CFGs, performs isomorphism detection, and stores results
- **Elasticsearch Database** - Stores unique CFGs and isomorphism relationships

## Workflow

1. The user sends a request with desired repositories (GitHub URLs)
2. The request is processed only if it hasn't been processed before (processed URLs are stored in the `repos` index). If not yet processed, the request is sent to the appropriate Kafka topic based on the language configuration
3. The CFG processor (specific to each language) consumes messages from its topic, clones the repository, and generates CFGs for each source file
4. Generated CFGs are validated and sent to the CFG topic
5. The Elasticsearch uploader consumes CFGs, checks for isomorphic graphs in the database, and stores them accordingly

---

## API Usage

### Scrape a Single Repository

**Endpoint:** `POST http://localhost:8000/scrap`

```bash
curl -X POST http://localhost:8000/scrap \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://github.com/username/repository",
    "language_topic": "python",
    "files_extension": ".py"
  }'
```

**Request Body:**
| Field | Type | Description |
|-------|------|-------------|
| `url` | string | Git repository URL |
| `language_topic` | string | Language processor to use (must be in `LANGUAGE_TOPICS`, e.g. `python`, `cpp17`, `java`, `javascript`, `typescript`) |
| `files_extension` | string | File extension to process (e.g., `.py`, `.cpp`, `.java`, `.js`, `.ts`) |
| `options` | object | Optional additional configuration |

### Scrape Multiple Repositories

**Endpoint:** `POST http://localhost:8000/scrap_multiple`

```bash
curl -X POST http://localhost:8000/scrap_multiple \
  -H "Content-Type: application/json" \
  -d '{
    "url_list": [
      "https://github.com/user/repo1",
      "https://github.com/user/repo2"
    ],
    "language_topic": "python",
    "files_extension": ".py"
  }'
```

---

## Adding a New Language Scraper

To add support for a new programming language, follow these steps:

### Step 1: Create Language Scraper Directory

```bash
mkdir -p src/language_scrapers/<language>/
```

### Step 2: Create `repo_script.sh`

This script should clone and build the repository. Because the build procedure can differ based on the language, the script should be created separately for each language.

### Step 3: Create `cfg_build_script.sh` 

This script generates a CFG for a single file (should take the filepath as a parameter). The script is kept as a shell script based on the assumption that users may want to use their preferred language/tools for CFG generation.

### Step 4: Create Dockerfile

Create `Dockerfile.CfgProcessor<Language>`:

The Dockerfile should set up everything necessary for the processor, including installing libraries for CFG generation. 

The new processor is based on `processors.cfg_processor`, which runs the `repo_script.sh` script. After that, it iterates through files in the directory that have the appropriate extension and runs `cfg_build_script.sh` with the filename as a parameter.

Existing solutions also run `file_to_cfg.py` inside `cfg_build_script.sh`, which is responsible for building the CFG from a single file. **The solution must print the result (CFG) as JSON to standard output** - the CFG processor uses this output for validation and further processing.

Java support follows the same architecture: the Python `cfg_processor` remains the worker, and `src/language_scrapers/java/*.sh` invoke a standalone Java CLI process (Soot-based) to generate CFG JSON. No additional Java web middleware is required.

JavaScript and TypeScript share an analogous setup: a single Node CLI in `js-cfg-cli/` (the JS/TS counterpart of `java-cfg-cli/`) uses ESLint
's [code path analysis](https://eslint.org/docs/latest/extend/code-path-analysis) to produce a CFG per function and program. The `javascript` 
and `typescript` scrapers in `src/language_scrapers/` are thin wrappers around this CLI; the parser is selected automatically from the file ex
tension (`@typescript-eslint/parser` for `.ts`/`.tsx`/`.mts`/`.cts`, `espree` otherwise).

### Step 5: CFG Validation

The CFG processor validates all generated graphs using Pydantic models before sending them to Elasticsearch. The validation includes:

**Graph Structure Validation:**
- All target nodes referenced in the adjacency list must exist as keys in the graph
- Invalid references (edges pointing to non-existent nodes) will raise a validation error

**Automatic Field Computation:**
- `out_degrees` - Sorted list of outgoing edge counts for each node (used for isomorphism pre-filtering)
- `in_degrees` - Sorted list of incoming edge counts for each node (used for isomorphism pre-filtering)

**Required Output Format:**

The `cfg_build_script.sh` (or `file_to_cfg.py`) must output valid JSON with the following structure:

```json
{
  "filepath": "/path/to/file.py",
  "graphs": [
    {
      "name": "function_name_1",
      "graph_dict": {
        "B1": ["B2"],
        "B2": ["B3", "B4"],
        "B3": ["B5"],
        "B4": ["B5"],
        "B5": []
      }
    },
    {
      "name": "function_name_2",
      "graph_dict": {
        "B1": ["B2", "B4"],
        "B2": ["B3"],
        "B3": ["B4"],
        "B4": [],
      }
    }
  ]
}
```

Where `graph_dict` is an adjacency list representation: `{node: [list_of_successor_nodes]}`.

### Step 6: Add Service to `docker-compose.yaml`

```yaml
cfg-processor-<language>:
  build:
    context: .
    dockerfile: Dockerfile.CfgProcessor<Language>
  container_name: cfg-processor-<language>
  environment:
    LANGUAGE: ${<LANGUAGE>_CFG_LANGUAGE}
    EXTENSION: ${<LANGUAGE>_CFG_EXTENSION}
    KAFKA_BROKER: kafka:${KAFKA_HOST}
    GRAPH_KAFKA_TOPIC: ${GRAPH_KAFKA_TOPIC}
    LANGUAGE_TOPICS: ${LANGUAGE_TOPICS}
    REPO_SCRIPT: ${<LANGUAGE>_CFG_REPO_SCRIPT}
    CFG_BUILD_SCRIPT: ${<LANGUAGE>_CFG_BUILD_SCRIPT}
    ELASTIC_HOST: http://elasticsearch:${ELASTIC_HOST}
    ERROR_INDEX: ${ERROR_INDEX}
    LOGGING_TO_ELASTIC: ${LOGGING_TO_ELASTIC}
    REPO_FOLDER: ${<LANGUAGE>_REPO_FOLDER}
  depends_on:
    kafka:
      condition: service_healthy
    elasticsearch:
      condition: service_healthy
```

### Step 7: Add Environment Variables to `.env`

```env
<Language> CFG Processor
<LANGUAGE>_CFG_LANGUAGE=<language>
<LANGUAGE>_CFG_EXTENSION=.<ext>
<LANGUAGE>_CFG_REPO_SCRIPT=/app/src/language_scrapers/<language>/repo_script.sh
<LANGUAGE>_CFG_BUILD_SCRIPT=/app/src/language_scrapers/<language>/cfg_build_script.sh
<LANGUAGE>_REPO_FOLDER=/<language>_repos
```

Also add the language to `LANGUAGE_TOPICS`:
```env
LANGUAGE_TOPICS=python,cpp,<language>
```

### Step 8: Rebuild and Test

```bash
make rebuild
```

---

## Makefile Commands

| Command | Description |
|---------|-------------|
| `make build` | Build and start all containers |
| `make rebuild` | Remove all containers/images and rebuild from scratch |

---

## Viewing Results

### Kibana

Access Kibana at `http://localhost:5601` to explore the indexed CFGs.

### Elasticsearch Indices

- `cfg_index` - Unique CFGs (non-isomorphic)
- `cfg_isomorphism_index` - Graphs that are isomorphic to existing ones
- `repos` - Processed repository URLs
- `error_logs` - Processing errors
