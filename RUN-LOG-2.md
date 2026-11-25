# RUN-LOG-2 (task 2, jOOQ docs MCP available)

MCP tools were available (6 tools loaded via ToolSearch). Read README, TASK, TASK-2, V1-V3, PostingBook, TransferService, CustomerReportService, AbstractLedgerTest.

## MCP calls
1. getCodeGenerationGuide () - returned only whitespace/Generator config fragments, nothing on forced types. Not helpful; no change.
2. searchDocumentation "forcedTypes converter code generation custom data type" - helpful: confirmed `<forcedType><userType>..</userType><converter>..</converter><includeExpression>` shape ("Qualified converters"). Snippets truncated, so the exact element names for includeExpression/includeTypes come from my memory (guess, will see at codegen).
3. searchDocumentation "recursive CTE withRecursive" - confirmed `DSLContext.withRecursive(...)` / DSL.name(..).fields(..).as(...) pattern, with a path example (`t.path || '\' || label`). Confirmatory.
4. searchDocumentation "generateSeries generate_series table function" - only showed emulations for Sybase/DB2/SQLDW (recursive CTE), confirms `generateSeries` exists and is emulated. Confirmatory; I will use it for the day axis.

## Decisions / guesses
- Item 5 (Iban): record `Iban` + `IbanConverter` (AbstractConverter) in main, forced type in pom (userType/converter/includeExpression `.*\.account\.iban`). Worried that the converter class (compiled later than generate-sources) would be needed on the codegen classpath: it was NOT; generator only emits the class name. Generated `Account.IBAN` is `TableField<AccountRecord, Iban>`. No compile errors, all tests green on first try (IbanTest 5 + old 17 = 22).
- FX clearing accounts now use `Iban.generate("CH", "FXCLEARING"+currency)` (valid check digits). Test helper `account()` generates valid IBANs from a counter. Existing databases that already hold e.g. `FX-CLEARING-CHF` would fail on read (converter throws); there is no seed data with IBANs in V1-V3, so no migration written.
- Test output of mvn is very noisy (DEBUG SQL); I read surefire XML for totals.
