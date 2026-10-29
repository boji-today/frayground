# Develop boundary audit

Review of `security/develop-boundary-validation` against `main` (`71f5da4`) and `develop` (`6cba75e`). This record separates safeguards already committed on the review branch from gaps that stay deferred. No HTTP controller or action submitter exists, so storage and credential checks are in-process boundaries, not remote exposure claims.

## Scope and base

`develop` at `6cba75e` is the feature base. Besides the four named feature commits (Connect Four rules and codec, game registry and match init, in-memory storage, stateless agent runtime), that base also contains domain invariant tests (`8952566`) and the DeepSeek flash adapter (`a658286`).

This branch adds only the three security commits below. It does not change Connect Four rules, codecs, domain records, scheduling, context shape, mock runtime, or DeepSeek request construction.

## Domain comparison

`git diff --name-status 71f5da4 6cba75e -- backend/src/main/java/io/frayground/domain` shows additions only. No pre-existing domain type was modified versus `main`.

Six Connect Four types were added under `backend/src/main/java/io/frayground/domain/game/connectfour`: `Cell`, `PlaceDisc`, `ConnectFourObservation`, `ConnectFourState`, `ConnectFourRules`, `ConnectFourDefinition`.

Identity stays raw UUIDs. `TurnContext` has `turnId` and `requiredParticipants` and no `matchId`. Cross-aggregate ownership therefore cannot live on the turn record; the memory adapter keeps it in `turnMatches` and checks it at write time.

## Requirements, design, and tests

Connect Four rules, the JSON codec, and registry/init match `backend/DESIGN.md` where they exist: a registered definition creates a `READY` match, participants, an opening turn, and a version-0 snapshot. Current tests exercise board bounds, some wins and draws, codec round-trips, and illegal action rejection inside the definition. That is covered behavior, not a closed lock of `docs/07-testing.md`. Scheduler, room reset orchestration, and a running match loop are not in this tree.

`StatelessContextBuilder` builds one fixed input from the current observation, options, prompt, and rules text. It does not append prior model turns. `MockAgentRuntime` selects the first `ACTION_SELECTION` option and does not claim that option is legal.

When memory mode is active, state stays in the process. `backend/DESIGN.md` states that a new instance starts empty and does not meet cross-restart history retention. Reset remains a domain command type; no application service performs it. The tracked default is not memory; see the mismatch below.

### Test boundaries not locked

- Played games do not directly assert winner or draw standings, ranks, and reasons (`FOUR_IN_A_ROW` / `BOARD_FULL`). State validation reuses the same result helpers, so it is not an independent oracle.
- The `RegisteredMatchFactory` test does not compare the entire decoded opening snapshot (participant order, every empty cell, `moveCount`). Direct codec tests cover round trips separately.
- Registry tests use one registration and do not prove that two registered games cannot return each other's codec or schema.
- The exact fixed vertical and horizontal sequences in `docs/07-testing.md` (A horizontal `0,6,1,6,2,5,3`; A vertical `0,1,0,1,0,1,0`) are not the direct test sequences. Equivalent win directions are covered.
- Reset isolation of the old match from the new one is neither implemented nor tested. Memory adapters do not deliver reset.
- Stateless tests prove that history is not appended and that prompt and config `agentId`s agree. They do not prove that a participant is bound to that agent's prompt or observation.

### Documentation mismatches

- `docs/07-testing.md` still opens by saying there is no business code or business test result. That statement is stale on `develop`.
- `backend/DESIGN.md` and `docs/08-implementation-plan.md` say the dev profile selects memory. There is no `application-dev.yml`, and the tracked `application.yml` defaults `frayground.storage.mode` to `database`. This is a configuration/docs mismatch only.

## Implemented safeguards

These checks reject only inconsistent constructed identities. A legal write whose ids already agree still succeeds. No game rule, status transition, or model request field changed.

`5714acc` `fix(memory): enforce aggregate ownership`

- `InMemoryRoomRepository.replace` requires a non-null `activeMatchId` to exist and to belong to that room.
- `InMemoryMatchRepository.replace` requires a non-null `currentTurnId` to map to that match. `insert` does not, because the match is stored before its turn.
- `InMemoryTurnRepository.insert` requires every required participant to belong to the owning match and `baseStateVersion` to equal `Match.stateVersion`. `replace` requires an existing owner mapping.
- `InMemoryGameEventRepository.append` allows a null `decisionId`; a present id must exist on that match.
- `InMemoryRoomEventRepository.append` allows a null `matchId`; a present id must exist in that room.

`1fea9c2` `fix(memory): bind decision and receipt identities`

- `InMemoryDecisionRepository.insert` keeps the match, turn, and match-participant checks, and also requires the participant to be required for the turn and `stateVersion` to equal the turn base version. It does not add current-turn, status, or retry-uniqueness rules. `replace` and `transition` are unchanged.
- `InMemoryActionReceiptRepository.saveFinal` requires the envelope match, turn, participant, and base `stateVersion` to equal the decision. The result version is not compared, so an `EXECUTED` receipt can record the settled version.
- `InMemorySettlementRepository.save` requires each settled participant to equal that decision's participant.
- `InMemoryDecisionRecordRepository` insert and replace require the decision to exist. A non-null submission result must use that decision id and that decision's match. Its state version is not compared.
- `InMemoryCommandReceiptRepository.save` requires `result.commandId` to equal the command argument, and the result match to exist in the supplied room. An identical receipt can still be saved again.

`4659ef4` `fix(model): harden credential resolution`

- `SystemModelCredentialProvider.resolve` returns empty when `supports` is false, and only then reads `apiKeys` for `configuration.modelId()`.
- `supports` is unchanged: only `system:<modelId>` whose suffix equals `configuration.modelId()`.

## Model API key

The production wiring is `ModelAdapterConfiguration` to `CompositeModelCredentialProvider` to `SystemModelCredentialProvider` to `DeepSeekModelAdapter`.

A correct `system:<modelId>` uses that platform key. `user:`, `unknown:`, a null reference, and `system:` for a different model id resolve empty. `DeepSeekModelAdapter` treats a missing or unsupported credential as `MODEL_CONFIGURATION_INVALID` and does not send HTTP. A supported call puts the key only in the `Authorization: Bearer` header. The chat body and error text do not carry it.

No user credential provider is registered. User-to-user key isolation is therefore not implemented and not applicable yet. Every supported `system:` call shares the platform key and its billing. An empty or blank configured key stays empty and does not fall through to another provider.

The tracked `backend/src/main/resources/application.yml` baseline sets `frayground.model.system-api-keys.deepseek-flash` to an empty string. A pre-existing local modification of that file was left unstaged and is not in these commits.

## Deferred

Left open because a fix would change business or API architecture:

- Read authorization on child-id `find` methods, until a controller and principal exist.
- Binding a participant to that agent's prompt and configuration, until `AgentRunner` has an application orchestration.
- Scoping `visibleHistory` to the expected match, until a `VisibleHistoryReader` exists. `ConnectFourDefinition.visibleHistory` filters event fields for a seat; it does not check match ownership.
- Reset, lifecycle, and a single active decision per scheduling phase.
- A user-level credential provider and per-user key ownership.

## Verification

The parent ran the full backend suite after each code commit. The final result is 117 tests, 0 failures, 0 errors. The pre-existing `application.yml` modification was not part of any commit.
