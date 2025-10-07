# Отчет по тестированию Agent API

**Дата:** 6 октября 2025  
**Тестируемый компонент:** AgentChatController  
**Конфигурация:** solver=groq, verifier=ollama  
**Запрос:** "что ты умеешь делать"

---

## 1. Выполненный запрос

```bash
POST http://localhost:8181/api/agent/chat/test_conv_001?solverProvider=groq&verifierProvider=ollama
Content-Type: application/json

{
  "message": "что ты умеешь делать"
}
```

---

## 2. Анализ логов запуска приложения

### 2.1 Успешная инициализация

✅ **Подключение к базам данных:**
- HikariPool-1 успешно запущен (PostgreSQL)
- Redis подключен успешно
- R2DBC репозитории инициализированы (2 найдено)

✅ **Аутентификация внешних API:**
- ENU Portal API: успешный логин (cookie: glqu5uupf602cadr4mp7v8nc5r)
- Platonus API: успешный логин (sid: 4aaa9afbe7cf67dec7d953dca7898cc6, token: 140cac02-0000-4168-af75-3d7ec41c9e88)

✅ **RAG система:**
- RedisVectorStore создан с индексом: enu_redis_documents
- ParagraphPdfDocumentReader загружен для PDF документа о ЕНУ
- Обнаружена иерархия факультетов (14 факультетов)
- TokenTextSplitter настроен (размер чанка: 300, перекрытие: 100)

✅ **ML модели:**
- DJL model загружена: sentence-transformers/LaBSE
- PyTorch engine инициализирован (8 inter-op threads, 8 intra-op threads)
- Подготовлены embeddings для 3 инструментов (tools)

✅ **Инструменты (Tools):**
- `getGrades` - получение оценок студента
- `getPlatonusStudentSchedule` - получение расписания
- `getStaffInfo` - информация о сотрудниках

✅ **Сервер запущен:**
- Netty запущен на порту 8181
- Время запуска: 7.809 секунд

### 2.2 Предупреждения (Warnings)

⚠️ **Maven зависимости:**
```
'dependencies.dependency.(groupId:artifactId:type:classifier)' must be unique: 
org.projectlombok:lombok:jar -> duplicate declaration of version (?) @ line 124, column 21
```
**Рекомендация:** Удалить дублирующуюся зависимость Lombok из pom.xml (строка 124)

⚠️ **CUDA не найдена:**
```
No matching cuda flavor for win-x86_64 found: cu065
```
**Рекомендация:** Это нормально для CPU-режима. Если нужно GPU ускорение, установить CUDA toolkit.

⚠️ **Отсутствие @AgentToolMeta:**
```
Метод DateTimeTool.getCurrentDateTime помечен @Tool, но отсутствует @AgentToolMeta 
— тул не будет зарегистрирован
```
**Рекомендация:** Добавить аннотацию `@AgentToolMeta` к методу `getCurrentDateTime` в классе `DateTimeTool`

⚠️ **Spring Data Redis репозитории:**
```
Could not safely identify store assignment for repository candidate interface 
bbt.tao.orchestra.repository.ConversationRepository
```
**Рекомендация:** Добавить аннотацию `@RedisHash` к сущностям или использовать специфичные интерфейсы Redis

---

## 3. Анализ работы агента

### 3.1 Поток выполнения (Event Flow)

1. **SOLVER_STARTED** - Итерация 1 запущена
2. **RAG_CONTEXT** - Подготовлено 4 документа:
   - Филологический факультет (x2)
   - Военная кафедра (x2)
3. **TOOL_SELECTION_SKIPPED** - Инструменты не потребовались (No tool matched)
4. **SOLVER_TOKEN** - Генерация ответа токен за токеном (streaming)
5. **DRAFT_READY** - Черновик готов (135 символов)
6. **VERIFICATION_STARTED** - Начата верификация
7. **VERIFICATION_PROGRESS** - Проверка фактов и релевантности
8. **VERIFICATION_FEEDBACK** - Верификация завершена без проблем
9. **FINAL_ANSWER** - Результат доставлен

### 3.2 Ответ агента

```
Я могу отвечать на вопросы и предоставлять информацию, содержащуюся в контексте. 
Если информации нет в контексте, я скажу, что не знаю.
```

### 3.3 Метаданные ответа

- **Mode:** stream
- **Solver Provider:** groq
- **Verifier Provider:** ollama
- **Tool:** none (инструменты не использовались)
- **Documents:** 4 документа из RAG

### 3.4 Результат верификации

✅ **Верификация прошла успешно (ok: true)**

**Причины одобрения:**
1. Ответ соответствует функционалу модели: предоставляет информацию из контекста и объясняет ограничения
2. Формат ответа соответствует требованиям (текст без маркировки)
3. Ответ не требует дополнительных изменений, так как адекватно отвечает на вопрос пользователя

**Required Changes:** (пусто - изменения не требуются)

---

## 4. Оценка производительности

### 4.1 Положительные моменты

✅ **Архитектура Solver-Verifier работает корректно:**
- Groq успешно генерирует ответ
- Ollama успешно верифицирует качество ответа
- Потоковая передача работает (streaming tokens)

✅ **RAG система функционирует:**
- Документы извлекаются из векторного хранилища
- Релевантный контекст подается в модель

✅ **Классификатор инструментов работает:**
- Корректно определил, что инструменты не нужны для этого запроса

✅ **Система событий (SSE):**
- Server-Sent Events доставляют события в реальном времени
- Детальная трассировка всех этапов

### 4.2 Наблюдения

🔍 **RAG контекст:**
- Для вопроса "что ты умеешь делать" система извлекла документы о факультетах ЕНУ
- Это может быть не самый релевантный контекст для данного вопроса
- **Возможная причина:** Embedding модель сопоставила "умеешь делать" с образовательной тематикой

🔍 **Ответ слишком общий:**
- Агент не описал конкретные возможности (инструменты, функции)
- Не упомянул доступные инструменты: getGrades, getPlatonusStudentSchedule, getStaffInfo

---

## 5. Рекомендации по улучшению

### 5.1 Критические исправления

1. **Удалить дублирующуюся зависимость Lombok** (pom.xml, строка 124)
   ```xml
   <!-- Удалить одну из дублирующихся записей -->
   <dependency>
       <groupId>org.projectlombok</groupId>
       <artifactId>lombok</artifactId>
   </dependency>
   ```

2. **Добавить @AgentToolMeta к DateTimeTool**
   ```java
   @Tool(name = "getCurrentDateTime", description = "...")
   @AgentToolMeta(
       category = ToolCategory.UTILITY,
       displayName = "Current Date Time",
       description = "Gets current date and time"
   )
   public String getCurrentDateTime() { ... }
   ```

### 5.2 Улучшения RAG системы

3. **Добавить системный промпт с описанием возможностей:**
   ```java
   // В конфигурации ChatClient
   .defaultSystem("""
       Вы — ассистент ЕНУ со следующими возможностями:
       1. Получение расписания студента (getPlatonusStudentSchedule)
       2. Получение оценок студента (getGrades)
       3. Поиск информации о сотрудниках (getStaffInfo)
       4. Ответы на вопросы об университете на основе документов
       
       При вопросе "что ты умеешь" опишите эти возможности.
       """)
   ```

4. **Улучшить релевантность RAG:**
   - Добавить метаданные к документам (тип: факультет, контактная информация и т.д.)
   - Использовать гибридный поиск (векторный + keyword)
   - Настроить threshold для similarity score

### 5.3 Улучшения классификатора инструментов

5. **Расширить embeddings для вопросов о возможностях:**
   ```java
   // В EmbeddingToolClassifier добавить синонимы
   - "что ты умеешь"
   - "какие у тебя функции"
   - "что ты можешь делать"
   - "какие команды ты знаешь"
   ```

6. **Добавить специальный handler для self-description:**
   ```java
   @Tool(name = "describeCapabilities")
   public String describeCapabilities() {
       return """
           Я могу помочь вам с:
           - Просмотром вашего расписания
           - Проверкой ваших оценок
           - Поиском информации о преподавателях
           - Ответами на вопросы об университете
           """;
   }
   ```

### 5.4 Мониторинг и логирование

7. **Добавить метрики производительности:**
   - Время работы solver
   - Время работы verifier
   - Количество токенов
   - Стоимость запроса

8. **Структурированное логирование:**
   ```java
   log.info("Agent request completed: conversationId={}, solver={}, verifier={}, " +
            "iterations={}, toolsUsed={}, duration={}ms",
            conversationId, solverProvider, verifierProvider, 
            iterations, toolsUsed, duration);
   ```

### 5.5 Конфигурация Redis

9. **Добавить аннотации для репозиториев:**
   ```java
   @RedisHash("conversations")
   public class Conversation { ... }
   
   @RedisHash("messages")
   public class Message { ... }
   ```

### 5.6 Тестирование

10. **Добавить интеграционные тесты для Agent API:**
    ```java
    @Test
    void testAgentWithGroqAndOllama() {
        // Тест solver=groq, verifier=ollama
    }
    
    @Test
    void testToolSelection() {
        // Тест выбора инструментов
    }
    
    @Test
    void testVerificationFeedback() {
        // Тест процесса верификации
    }
    ```

---

## 6. Применение Chrome DevTools MCP

### 6.1 Текущая ситуация

❌ Chrome DevTools MCP не доступен:
```
Failed to fetch browser webSocket URL from http://127.0.0.1:9222/json/version: fetch failed
```

### 6.2 Рекомендации по настройке

1. **Запустить Chrome в режиме удаленной отладки:**
   ```powershell
   chrome.exe --remote-debugging-port=9222 --user-data-dir="C:\temp\chrome-debug"
   ```

2. **Возможные применения Chrome MCP для этого проекта:**
   - **Мониторинг Network requests:** Отслеживание API вызовов к Groq и Ollama
   - **Console logs:** Просмотр JavaScript ошибок на фронтенде (если есть)
   - **Performance profiling:** Анализ времени загрузки страниц
   - **Screenshot testing:** Автоматизированное тестирование UI
   - **DOM inspection:** Анализ структуры веб-страниц ЕНУ при скрапинге

3. **Пример использования для тестирования:**
   ```javascript
   // Автоматизация тестирования frontend
   await mcp_chromedevtool_navigate_page("http://localhost:8181/actuator/health");
   await mcp_chromedevtool_take_screenshot({name: "health_check"});
   
   // Мониторинг API вызовов
   await mcp_chromedevtool_list_network_requests({
       resourceTypes: ["xhr", "fetch"]
   });
   ```

---

## 7. Заключение

### 7.1 Общая оценка

✅ **Система работает корректно:**
- Агент успешно обрабатывает запросы
- Solver-Verifier архитектура функционирует
- RAG система извлекает документы
- SSE стриминг работает

⚠️ **Требуются улучшения:**
- Качество ответов на meta-вопросы ("что ты умеешь")
- Релевантность RAG контекста
- Конфигурация и мониторинг

### 7.2 Приоритеты

**Высокий приоритет:**
1. Исправить дублирование Lombok dependency
2. Добавить @AgentToolMeta к DateTimeTool
3. Улучшить промпт для описания возможностей

**Средний приоритет:**
4. Настроить Redis аннотации
5. Добавить метрики и мониторинг
6. Расширить тесты

**Низкий приоритет:**
7. Настроить Chrome DevTools MCP
8. Оптимизировать RAG similarity threshold
9. Добавить гибридный поиск

### 7.3 Следующие шаги

1. Протестировать с реальными запросами (расписание, оценки)
2. Проверить работу tool selection
3. Измерить производительность на нагрузке
4. Настроить мониторинг в production

---

## 8. Дополнительный тест: Запрос расписания

### 8.1 Выполненный запрос

```bash
POST http://localhost:8181/api/agent/chat/test_conv_002?solverProvider=groq&verifierProvider=ollama
{
  "message": "покажи мое расписание на эту неделю"
}
```

### 8.2 Результаты теста

**Общее время выполнения:** ~3 минуты 21 секунда

**Количество итераций:** 3 (две итерации были отклонены verifier'ом)

#### Итерация 1:
- ✅ **Tool Selection:** getPlatonusStudentSchedule выбран корректно
- ✅ **Tool Execution:** Инструмент выполнился успешно
- ✅ **Solver:** Сгенерировал расписание (только понедельник)
- ❌ **Verifier:** Отклонил (ok: false)

**Причины отклонения:**
1. Отсутствие полного расписания для всех дней недели (только понедельник представлен)
2. Не указано, как определена дата (06 октября 2025) - возможно, это placeholder
3. Упоминание 'Platonus' не объясняется, хотя в RAG-документах указаны факультеты, не связанные с этим
4. Нет ссылки на конкретные данные студента (например, группа, факультет)

#### Итерация 2:
- ✅ **Tool Selection:** getPlatonusStudentSchedule выбран снова
- ✅ **Tool Execution:** Инструмент выполнился успешно
- ✅ **Solver:** Сгенерировал то же расписание
- ❌ **Verifier:** Отклонил (ok: false) с теми же замечаниями

#### Итерация 3:
- ✅ **Tool Selection:** getPlatonusStudentSchedule выбран третий раз
- ✅ **Tool Execution:** Инструмент выполнился успешно  
- ✅ **Solver:** Сгенерировал то же расписание
- ⚠️ **Verifier:** Принял ответ как final (окончание после 3 итераций)

**Финальный ответ:**
Расписание на понедельник с 4 парами по дисциплине "Алгоритмические основы интеллектуальных систем" (онлайн занятия)

### 8.3 Проблемы, выявленные в тесте

1. **Verifier слишком строгий:**
   - Требует расписание на всю неделю, но API возвращает только понедельник
   - Возможно, в базе данных нет расписания на другие дни

2. **Бесконечный цикл исправлений:**
   - Solver не может исправить проблемы, так как данные приходят из инструмента
   - Verifier требует невозможного (данные, которых нет в системе)
   - Цикл остановился только после 3 итераций (лимит?)

3. **RAG контекст не релевантен:**
   - Для запроса расписания извлечены документы о факультетах
   - Это не помогает ни solver'у, ни verifier'у

4. **Производительность:**
   - 3+ минуты на простой запрос расписания
   - Множественные вызовы одного и того же инструмента
   - Каждая итерация занимает ~1 минуту
    
### 8.4 Рекомендации по улучшению

**Высокий приоритет:**

1. **Настроить логику Verifier:**
   ```java
   // Добавить проверку на доступность данных
   if (toolResult.isEmpty() || toolResult.contains("нет данных")) {
       return VerificationResult.accept("Данные ограничены системой");
   }
   ```

2. **Добавить limit на итерации:**
   ```java
   @Value("${agent.max-iterations:3}")
   private int maxIterations;
   
   if (iteration >= maxIterations) {
       log.warn("Max iterations reached, accepting current draft");
       return VerificationResult.accept("Лимит итераций достигнут");
   }
   ```

3. **Кэшировать результаты инструментов:**
   ```java
   @Cacheable(value = "scheduleCache", key = "#userId + #week")
   public ScheduleResponse getSchedule(String userId, int week) {
       // ...
   }
   ```

4. **Улучшить Verifier промпт:**
   ```
   При верификации учитывай:
   - Если данные получены из tool, они считаются достоверными
   - Не требуй данных, которых нет в tool output
   - Проверяй только формат и релевантность ответа
   ```

**Средний приоритет:**

5. **Добавить метрики:**
   - Время выполнения каждой итерации
   - Количество итераций до принятия
   - Процент отклонений verifier'ом

6. **Логировать причины отклонения:**
   ```java
   log.info("Verification failed: iteration={}, reasons={}", 
            iteration, feedback.reasons());
   ```

7. **Оптимизировать RAG:**
   - Не запускать RAG для tool-based запросов
   - Использовать разные embedding модели для разных типов запросов

---

**Подготовлено:** GitHub Copilot  
**Основано на логах от:** 6 октября 2025, 22:21:32  
**Обновлено:** Добавлен анализ запроса расписания с 3 итерациями
