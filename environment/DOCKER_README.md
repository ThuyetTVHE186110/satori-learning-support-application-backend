# Satori Japanese Learning System - Docker Environment

## 🏗️ Architecture Overview

Hệ thống sử dụng **PostgreSQL + pgvector** làm database chính, kết hợp **Redis** cho caching:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        SATORI LEARNING SYSTEM                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────────────────────────┐    ┌─────────────────────────────┐   │
│  │      PostgreSQL + pgvector        │    │           Redis             │   │
│  │         (Primary Database)        │    │          (Cache)            │   │
│  └───────────────────────────────────┘    └─────────────────────────────┘   │
│                    │                                    │                    │
│                    ▼                                    ▼                    │
│  ┌───────────────────────────────────┐    ┌─────────────────────────────┐   │
│  │  • Users, Classes, Lessons        │    │  • Session tokens           │   │
│  │  • Vocabulary, Grammar            │    │  • Streak cache             │   │
│  │  • Assignments, Submissions       │    │  • Leaderboard cache        │   │
│  │  • AI Conversations (JSONB)       │    │  • Translation cache        │   │
│  │  • Progress, Gamification         │    │  • API response cache       │   │
│  │  • Vector Embeddings (pgvector)   │    │                             │   │
│  │  • Pronunciation Practice         │    │                             │   │
│  └───────────────────────────────────┘    └─────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 📊 Database Selection Rationale

| Component            | Technology         | Reasons                                             |
| -------------------- | ------------------ | --------------------------------------------------- |
| **Primary DB**       | PostgreSQL 16      | ACID compliance, complex relations, data integrity  |
| **Vector Search**    | pgvector extension | AI embeddings, semantic search, recommendations     |
| **JSON Storage**     | PostgreSQL JSONB   | Flexible AI conversation data, analytics            |
| **Full-text Search** | pg_trgm extension  | Vietnamese/Japanese search support                  |
| **Caching**          | Redis 7.2          | Low latency, session management, real-time features |

---

## 🗄️ PostgreSQL Schema Structure

```
PostgreSQL Schemas (10 schemas, 46+ tables):
├── user_management/
│   ├── users                      # User accounts, authentication
│   ├── user_learning_preferences  # AI context settings, personalization
│   ├── classes                    # Teacher classes
│   ├── class_students             # Class enrollment (M:N)
│   └── refresh_tokens             # JWT refresh tokens
│
├── learning_content/
│   ├── courses                    # Curriculum/textbooks
│   ├── lessons                    # Individual lessons
│   ├── vocabulary                 # Vocabulary items
│   ├── grammar_points             # Grammar explanations
│   ├── lesson_vocabulary          # Lesson-vocabulary mapping (M:N)
│   └── lesson_grammar             # Lesson-grammar mapping (M:N)
│
├── pronunciation/
│   ├── pronunciation_topics       # Standalone pronunciation topics
│   ├── pronunciation_items        # Practice items (audio, text)
│   ├── user_topic_progress        # User progress by topic
│   ├── user_item_progress         # User progress by item
│   └── pronunciation_attempts     # Practice history with AI analysis (JSONB)
│
├── ai_conversation/
│   ├── conversation_topics        # AI conversation scenarios
│   ├── conversation_sessions      # Chat sessions (JSONB context)
│   ├── conversation_messages      # Messages with corrections (JSONB)
│   └── translation_cache          # Translation cache
│
├── assignment/
│   ├── quiz_questions             # Question bank
│   ├── assignments                # Teacher-created assignments
│   ├── assignment_questions       # Assignment-question mapping
│   ├── assignment_targets         # Assignment to class/user
│   └── assignment_submissions     # Submissions with answers (JSONB)
│
├── progress/
│   ├── daily_activity             # Daily learning stats (JSONB analytics)
│   ├── user_progress              # Overall progress summary
│   └── content_embeddings         # AI content vectors (pgvector)
│
├── gamification/
│   ├── user_stats                 # EXP, level, coins, streaks
│   ├── badges                     # Badge definitions
│   ├── user_badges                # Earned badges
│   ├── leaderboard                # Rankings
│   └── reward_transactions        # EXP/coin ledger
│
├── shop/
│   ├── items                      # Shop items
│   ├── user_items                 # Purchased items
│   └── purchase_history           # Purchase records
│
└── system_admin/
    ├── usage_statistics           # System usage stats
    ├── import_history             # Data import logs
    ├── curriculum_imports         # PDF curriculum processing
    ├── import_chunks              # Extracted text chunks
    ├── audit_log                  # Action audit trail
    └── notification_queue         # Notification delivery
```

---

## 🚀 Quick Start

### Prerequisites

- **Docker Desktop** installed and running
- At least **4GB RAM** allocated to Docker
- **Ports available**: 5432, 6379, 8081, 8083

### Step 1: Navigate to Environment Folder

```powershell
cd environment
```

### Step 2: Create Environment File

```powershell
# Copy template
Copy-Item .env.example .env

# Or create manually with these values:
@"
# PostgreSQL
POSTGRES_PASSWORD=SatoriSecure2026!

# Redis
REDIS_PASSWORD=SatoriRedis2026!
"@ | Out-File -FilePath .env -Encoding utf8
```

### Step 3: Start Services

```powershell
# Start all services (PostgreSQL + Redis + Admin UIs)
docker-compose up -d

# Check status
docker-compose ps

# View logs (follow mode)
docker-compose logs -f
```

### Step 4: Verify Services

```powershell
# Check PostgreSQL
docker exec -it satori-postgres psql -U satori_user -d satori_learning -c "SELECT version();"

# Check pgvector extension
docker exec -it satori-postgres psql -U satori_user -d satori_learning -c "SELECT * FROM pg_extension WHERE extname = 'vector';"

# Check Redis
docker exec -it satori-redis redis-cli -a SatoriRedis2026! ping
```

---

## 🌐 Access Management UIs

| Service             | URL                   | Credentials                                                                                                     |
| ------------------- | --------------------- | --------------------------------------------------------------------------------------------------------------- |
| **Adminer** (DB UI) | http://localhost:8081 | System: `PostgreSQL`, Server: `postgres`, User: `satori_user`, Pass: `SatoriSecure2026!`, DB: `satori_learning` |
| **Redis Commander** | http://localhost:8083 | No auth required (connects automatically)                                                                       |

---

## 🔗 Application Connection Strings

### application.yml (Spring Boot)

```yaml
spring:
    datasource:
        url: jdbc:postgresql://localhost:5432/satori_learning
        username: satori_user
        password: SatoriSecure2026!
        driver-class-name: org.postgresql.Driver

    jpa:
        hibernate:
            ddl-auto: validate
        properties:
            hibernate:
                dialect: org.hibernate.dialect.PostgreSQLDialect
                default_schema: user_management

    data:
        redis:
            host: localhost
            port: 6379
            password: SatoriRedis2026!
```

### Environment Variables

```properties
# PostgreSQL
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/satori_learning
SPRING_DATASOURCE_USERNAME=satori_user
SPRING_DATASOURCE_PASSWORD=SatoriSecure2026!

# Redis
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=SatoriRedis2026!
```

---

## 🔧 Common Commands

### Docker Compose

```powershell
# Start services
docker-compose up -d

# Stop services (keep data)
docker-compose down

# Stop and remove ALL data (DANGER!)
docker-compose down -v

# Restart specific service
docker-compose restart postgres

# View logs
docker-compose logs -f postgres
docker-compose logs -f redis
```

### PostgreSQL

```powershell
# Connect to PostgreSQL shell
docker exec -it satori-postgres psql -U satori_user -d satori_learning

# List all schemas
docker exec -it satori-postgres psql -U satori_user -d satori_learning -c "\dn"

# List tables in a schema
docker exec -it satori-postgres psql -U satori_user -d satori_learning -c "\dt user_management.*"

# Run SQL file
docker exec -i satori-postgres psql -U satori_user -d satori_learning < your_script.sql

# Backup database
docker exec satori-postgres pg_dump -U satori_user satori_learning > backup_$(Get-Date -Format "yyyyMMdd_HHmmss").sql

# Restore database
Get-Content backup.sql | docker exec -i satori-postgres psql -U satori_user -d satori_learning
```

### Redis

```powershell
# Connect to Redis CLI
docker exec -it satori-redis redis-cli -a SatoriRedis2026!

# Check all keys
docker exec -it satori-redis redis-cli -a SatoriRedis2026! KEYS "*"

# Clear all cache (DANGER!)
docker exec -it satori-redis redis-cli -a SatoriRedis2026! FLUSHALL

# Check memory usage
docker exec -it satori-redis redis-cli -a SatoriRedis2026! INFO memory
```

---

## 📁 File Structure

```
environment/
├── docker-compose.yml          # Development (databases + admin UIs)
├── docker-compose.prod.yml     # Production (databases + app)
├── Dockerfile                  # Multi-stage build for Java app
├── .env.example                # Environment variables template
├── .env                        # Your local environment (gitignored)
├── DOCKER_README.md            # This file
└── docker/
    └── init-scripts/
        └── postgres/
            ├── 01-init.sql         # Schema creation (v2.0.0)
            └── 02-schema-fixes.sql # Schema fixes (v2.0.3)
```

---

## 🔐 Default Credentials

### Development Only (CHANGE IN PRODUCTION!)

| Service        | Username    | Password          |
| -------------- | ----------- | ----------------- |
| **PostgreSQL** | satori_user | SatoriSecure2026! |
| **Redis**      | (no user)   | SatoriRedis2026!  |

---

## 🛠️ Troubleshooting

### PostgreSQL không connect được

```powershell
# 1. Check container status
docker ps | Select-String postgres

# 2. Check logs
docker logs satori-postgres

# 3. Check port availability
Test-NetConnection -ComputerName localhost -Port 5432

# 4. Restart container
docker-compose restart postgres
```

### Init scripts không chạy

```powershell
# Init scripts chỉ chạy khi volume trống
# Để chạy lại, xóa volume:
docker-compose down -v
docker-compose up -d
```

### Redis connection refused

```powershell
# 1. Check container
docker ps | Select-String redis

# 2. Test connection
docker exec -it satori-redis redis-cli -a SatoriRedis2026! ping

# Expected output: PONG
```

### Port đã được sử dụng

```powershell
# Find process using port
Get-NetTCPConnection -LocalPort 5432 | Select-Object OwningProcess
Get-Process -Id <PID>

# Or change port in docker-compose.yml
ports:
  - "5433:5432"  # Use 5433 instead
```

---

## 🚀 Running the Application

### Development Mode (IDE)

1. Start Docker services: `docker-compose up -d`
2. Open project in IntelliJ IDEA / VS Code
3. Run `StartApplication.java` or use Maven:

```powershell
# From project root
./mvnw spring-boot:run -pl start
```

### Production Mode (Docker)

```powershell
# Build and start everything
docker-compose -f docker-compose.prod.yml up -d --build

# View application logs
docker-compose -f docker-compose.prod.yml logs -f app

# Scale application (if needed)
docker-compose -f docker-compose.prod.yml up -d --scale app=3
```

---

## 📊 Database Schema Version

| File                  | Version | Description                          |
| --------------------- | ------- | ------------------------------------ |
| `01-init.sql`         | 2.0.0   | Initial schema with all 46+ tables   |
| `02-schema-fixes.sql` | 2.0.3   | Audit fields, FK constraints, checks |

### Key Features in Schema v2.0.3:

- ✅ `updated_by` added to all auditable tables
- ✅ `deleted_by` added to all soft-delete tables
- ✅ FK constraints with proper ON DELETE policies
- ✅ CHECK constraints for business rules
- ✅ Meaning constraints (vi OR en required)
- ✅ Unique indexes for assignment targets

---

## 🛡️ Production Checklist

- [ ] Change all passwords in `.env`
- [ ] Enable SSL/TLS for database connections
- [ ] Set up database backups (pg_dump cron job)
- [ ] Configure connection pooling (HikariCP)
- [ ] Set up monitoring (Prometheus + Grafana)
- [ ] Configure log aggregation
- [ ] Set up Redis persistence (AOF)
- [ ] Use Docker secrets for credentials
- [ ] Set resource limits in docker-compose

---

## 📝 Notes

1. **First Run**: Init scripts automatically create schemas and tables
2. **Data Persistence**: Data stored in Docker volumes (`satori-postgres-data`, `satori-redis-data`)
3. **Schema Updates**: Add new migration files in `docker/init-scripts/postgres/` (numbered order)
4. **Extensions**: pgvector, uuid-ossp, pg_trgm are auto-installed

---

_Last Updated: 2026-02-01 | Schema Version: 2.0.3_
