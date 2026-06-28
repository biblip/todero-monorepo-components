package com.shellaia.tutil.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TodoManagerTest {
  @Test
  void createsGoalWithPhasesAndTasks() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());

    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.spotify",
        "Ship todo library",
        "First cut of plan storage",
        List.of(
            new TodoPhaseDraft("Design", "Clarify shape", List.of(
                new TodoTaskDraft("Draft API", "Define public API", null, 10, List.of(), null),
                new TodoTaskDraft("Choose store model", "Pick storage approach", null, 5, List.of(), null)
            ), null),
            new TodoPhaseDraft("Implement", "Build code", List.of(
                new TodoTaskDraft("Write manager", "Implement orchestration", null, 0, List.of(), null)
            ), null)
        ),
        null
    ));

    assertEquals(TodoStatus.READY, goal.status());
    assertEquals(2, goal.phases().size());
    assertEquals(2, goal.phases().get(0).tasks().size());
    assertEquals(3, goal.progress().taskCount());
    assertEquals(0, goal.progress().completedTasks());
    assertEquals(3, goal.progress().readyTasks());
  }

  @Test
  void updatingTaskStatusRollsUpToPhaseAndGoal() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.spotify",
        "Ship todo library",
        "",
        List.of(new TodoPhaseDraft("Implement", "", List.of(
            new TodoTaskDraft("Write manager", "", null, 0, List.of(), null),
            new TodoTaskDraft("Write tests", "", null, 0, List.of(), null)
        ), null)),
        null
    ));

    TodoPhase phase = goal.phases().get(0);
    TodoTask first = phase.tasks().get(0);
    TodoTask second = phase.tasks().get(1);

    TodoGoal step1 = manager.updateTaskStatus(goal.id(), phase.id(), first.id(), TodoStatus.IN_PROGRESS, "started");
    assertEquals(TodoStatus.IN_PROGRESS, step1.status());
    assertEquals(TodoStatus.IN_PROGRESS, step1.phases().get(0).status());

    TodoGoal step2 = manager.updateTaskStatus(goal.id(), phase.id(), first.id(), TodoStatus.COMPLETED, "done");
    assertEquals(TodoStatus.READY, step2.status());
    assertEquals(TodoStatus.READY, step2.phases().get(0).status());

    TodoGoal step3 = manager.updateTaskStatus(goal.id(), phase.id(), second.id(), TodoStatus.COMPLETED, "done");
    assertEquals(TodoStatus.COMPLETED, step3.status());
    assertEquals(TodoStatus.COMPLETED, step3.phases().get(0).status());
    assertEquals(2, step3.progress().completedTasks());
  }

  @Test
  void canInsertAndReorderPhasesAndTasks() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.spotify",
        "Launch",
        "",
        List.of(
            new TodoPhaseDraft("Phase A", "", List.of(new TodoTaskDraft("Task 1", "", null, 0, List.of(), null)), null),
            new TodoPhaseDraft("Phase B", "", List.of(new TodoTaskDraft("Task 2", "", null, 0, List.of(), null)), null)
        ),
        null
    ));

    TodoGoal withInsertedPhase = manager.insertPhase(
        goal.id(),
        new TodoPhaseDraft("Phase Inserted", "", List.of(new TodoTaskDraft("Task X", "", null, 0, List.of(), null)), null),
        1
    );
    assertEquals("Phase Inserted", withInsertedPhase.phases().get(1).title());

    TodoGoal reorderedPhases = manager.reorderPhases(withInsertedPhase.id(), 1, 0);
    assertEquals("Phase Inserted", reorderedPhases.phases().get(0).title());

    TodoPhase targetPhase = reorderedPhases.phases().get(0);
    TodoGoal withInsertedTask = manager.insertTask(
        reorderedPhases.id(),
        targetPhase.id(),
        new TodoTaskDraft("Task Y", "", null, 0, List.of(), null),
        0
    );
    assertEquals("Task Y", withInsertedTask.phases().get(0).tasks().get(0).title());

    TodoGoal reorderedTasks = manager.reorderTasks(withInsertedTask.id(), targetPhase.id(), 0, 1);
    assertEquals("Task X", reorderedTasks.phases().get(0).tasks().get(0).title());
    assertEquals("Task Y", reorderedTasks.phases().get(0).tasks().get(1).title());
  }

  @Test
  void supportsGoalAndTaskQueriesForAgentUse() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.spotify",
        "Ship spotify planner",
        "Agent-grade todo flow",
        List.of(
            new TodoPhaseDraft("Design", "", List.of(
                new TodoTaskDraft("Model goal states", "", Instant.parse("2026-05-23T12:00:00Z"), 10, List.of(), null),
                new TodoTaskDraft("Review transitions", "", null, 5, List.of(), null)
            ), null)
        ),
        null
    ));
    TodoPhase phase = goal.phases().get(0);
    TodoTask first = phase.tasks().get(0);
    manager.updateTaskStatus(goal.id(), phase.id(), first.id(), TodoStatus.IN_PROGRESS, "working");

    List<TodoGoal> openGoals = manager.listOpenGoals();
    assertEquals(1, openGoals.size());

    List<TodoGoal> textMatch = manager.queryGoals(new TodoGoalQuery("agent.spotify", Set.of(TodoStatus.IN_PROGRESS), "spotify", false));
    assertEquals(1, textMatch.size());

    List<TodoTaskRef> taskMatches = manager.queryTasks(new TodoTaskQuery(goal.id(), "agent.spotify", Set.of(TodoStatus.IN_PROGRESS), "model", false, false));
    assertEquals(1, taskMatches.size());
    assertEquals("Model goal states", taskMatches.get(0).taskTitle());

    TodoTaskRef nextTask = manager.nextReadyTask(goal.id()).orElseThrow();
    assertTrue(nextTask.status() == TodoStatus.IN_PROGRESS || nextTask.status() == TodoStatus.READY);
  }

  @Test
  void rocksDbStorePersistsGoals(@TempDir Path tempDir) {
    Path dbDir = tempDir.resolve("todo-rocks");

    TodoGoal saved;
    try (RocksDbTodoStore writerStore = new RocksDbTodoStore(dbDir)) {
      TodoManager writer = new TodoManager(writerStore, fixedClock());
      saved = writer.createGoal(new TodoGoalDraft(
          "agent.spotify",
          "Persist goal",
          "Verify RocksDB store",
          List.of(new TodoPhaseDraft("Phase 1", "", List.of(
              new TodoTaskDraft("Task 1", "", null, 0, List.of(), null)
          ), null)),
          null
      ));
    }

    assertTrue(Files.exists(dbDir));

    try (RocksDbTodoStore readerStore = new RocksDbTodoStore(dbDir)) {
      TodoManager reader = new TodoManager(readerStore, fixedClock());
      TodoGoal loaded = reader.findGoal(saved.id()).orElseThrow();

      assertEquals(saved.id(), loaded.id());
      assertEquals("Persist goal", loaded.title());
      assertEquals(1, reader.listGoals().size());
      reader.deleteGoal(saved.id());
      assertFalse(reader.findGoal(saved.id()).isPresent());
    }
  }

  @Test
  void dependenciesAndFacadeWorkForAgents() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoAgentFacade facade = new TodoAgentFacade(manager);
    TodoGoal goal = facade.plan(new TodoGoalDraft(
        "agent.dj",
        "DJ control flow",
        "",
        List.of(new TodoPhaseDraft("Playback", "", List.of(
            new TodoTaskDraft("Snapshot state", "", null, 5, List.of(), null),
            new TodoTaskDraft("Act on playlist", "", null, 10, List.of(), null)
        ), null)),
        null
    ));

    TodoPhase phase = goal.phases().get(0);
    TodoTask first = phase.tasks().get(0);
    TodoTask second = phase.tasks().get(1);
    goal = manager.updateTaskDetails(goal.id(), phase.id(), second.id(), second.title(), second.description(), second.dueAt(), 10, List.of(first.id()));

    TodoTaskRef next = facade.claimNextTask("agent.dj").orElseThrow();
    assertEquals(first.id(), next.taskId());
    assertEquals("agent.dj", next.claimedBy());
    assertTrue(next.claimExpiresAt().isAfter(next.claimedAt()));

    facade.completeTask(goal.id(), phase.id(), first.id(), "done");
    TodoTaskRef afterDependency = facade.claimNextTask("agent.dj").orElseThrow();
    assertEquals(second.id(), afterDependency.taskId());
  }

  @Test
  void batchUpdatesTasksInSingleMutation() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.batch",
        "Batch",
        "",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("One", "", null, 0, List.of(), null),
            new TodoTaskDraft("Two", "", null, 0, List.of(), null)
        ), null)),
        null
    ));
    TodoPhase phase = goal.phases().get(0);
    List<TodoGoal> result = manager.updateTaskStatuses(goal.id(), List.of(
        new TodoTaskStatusUpdate(phase.id(), phase.tasks().get(0).id(), TodoStatus.IN_PROGRESS, "started"),
        new TodoTaskStatusUpdate(phase.id(), phase.tasks().get(1).id(), TodoStatus.BLOCKED, "waiting")
    ));
    TodoGoal updated = result.get(0);
    assertEquals(TodoStatus.IN_PROGRESS, updated.phases().get(0).tasks().get(0).status());
    assertEquals(TodoStatus.BLOCKED, updated.phases().get(0).tasks().get(1).status());
    assertEquals(TodoStatus.IN_PROGRESS, updated.status());
  }

  @Test
  void rejectsDependencyCycles() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.cycle",
        "Cycle",
        "",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("One", "", null, 0, List.of(), null),
            new TodoTaskDraft("Two", "", null, 0, List.of(), null)
        ), null)),
        null
    ));
    TodoPhase phase = goal.phases().get(0);
    TodoTask first = phase.tasks().get(0);
    TodoTask second = phase.tasks().get(1);
    manager.updateTaskDetails(goal.id(), phase.id(), second.id(), second.title(), second.description(), second.dueAt(), second.priority(), List.of(first.id()));
    assertThrows(IllegalArgumentException.class, () ->
        manager.updateTaskDetails(goal.id(), phase.id(), first.id(), first.title(), first.description(), first.dueAt(), first.priority(), List.of(second.id())));
  }

  @Test
  void storeProtectsConcurrentUpdates() {
    InMemoryTodoStore store = new InMemoryTodoStore();
    TodoManager manager = new TodoManager(store, fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.spotify",
        "Concurrency",
        "",
        List.of(),
        null
    ));
    TodoGoal stale = goal;
    TodoGoal updated = manager.updateGoalDetails(goal.id(), "Concurrency updated", "");
    assertEquals(1L, updated.version());
    TodoGoal conflictingWrite = new TodoGoal(
        stale.id(),
        stale.ownerId(),
        stale.title(),
        stale.description(),
        stale.status(),
        stale.outcome(),
        stale.phases(),
        stale.metadata(),
        stale.createdAt(),
        stale.updatedAt(),
        stale.version() + 1
    );
    assertThrows(TodoConflictException.class, () -> store.save(conflictingWrite, stale.version()));
  }

  @Test
  void claimLeaseCanExpireAndBeRenewed() {
    InMemoryTodoStore store = new InMemoryTodoStore();
    TodoManager manager = new TodoManager(store, fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.lease",
        "Lease",
        "",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("One", "", null, 0, List.of(), null)
        ), null)),
        null
    ));
    TodoPhase phase = goal.phases().get(0);
    TodoTask task = phase.tasks().get(0);

    TodoGoal claimed = manager.claimTask(goal.id(), phase.id(), task.id(), "agent.lease", Duration.ofSeconds(30));
    TodoTask claimedTask = claimed.phases().get(0).tasks().get(0);
    assertEquals("agent.lease", claimedTask.claimedBy());

    TodoManager later = new TodoManager(store, Clock.fixed(Instant.parse("2026-05-22T12:01:00Z"), ZoneOffset.UTC));
    TodoGoal reClaimed = later.claimTask(goal.id(), phase.id(), task.id(), "agent.other", Duration.ofSeconds(30));
    assertEquals("agent.other", reClaimed.phases().get(0).tasks().get(0).claimedBy());

    TodoGoal renewed = later.renewTaskClaim(goal.id(), phase.id(), task.id(), "agent.other", Duration.ofMinutes(5));
    assertTrue(renewed.phases().get(0).tasks().get(0).claimExpiresAt().isAfter(renewed.phases().get(0).tasks().get(0).claimedAt()));
  }

  @Test
  void planningEditsReturnEventsAndSnapshot() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoGoal goal = manager.createGoal(new TodoGoalDraft(
        "agent.plan",
        "Plan edits",
        "",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("One", "", null, 0, List.of(), null)
        ), null)),
        null
    ));

    TodoMutationResult result = manager.applyPlanningEdits(goal.id(), List.of(
        TodoPlanningEdit.insertTask(goal.phases().get(0).id(), 1, new TodoTaskDraft("Two", "", Instant.parse("2026-05-23T12:00:00Z"), 10, List.of(goal.phases().get(0).tasks().get(0).id()), null)),
        TodoPlanningEdit.updateTask(goal.phases().get(0).id(), goal.phases().get(0).tasks().get(0).id(), "One updated", "", null, 5, List.of())
    ));

    assertEquals(2, result.goal().phases().get(0).tasks().size());
    assertEquals(2, result.events().size());
    assertEquals(TodoEventType.TASK_INSERTED, result.events().get(0).type());
    assertEquals(goal.id(), result.snapshot().goalId());
    assertEquals(result.goal().version(), result.snapshot().version());
  }

  @Test
  void canExportAndImportPlanDocumentJson() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoAgentFacade facade = new TodoAgentFacade(manager);
    TodoGoal goal = facade.plan(new TodoGoalDraft(
        "agent.exchange",
        "Interchange",
        "Exportable plan",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("Task A", "", Instant.parse("2026-05-23T12:00:00Z"), 10, List.of(), java.util.Map.of("kind", "scan"))
        ), java.util.Map.of("phaseType", "analysis"))),
        java.util.Map.of("channel", "service")
    ));

    String json = facade.exportGoalPlanJson(goal.id(), "agent:test");
    TodoPlanDocument document = facade.importPlanDocument(json);

    assertTrue(json.contains("\"schemaVersion\""));
    assertTrue(json.contains("\"source\" : \"agent:test\"") || json.contains("\"source\":\"agent:test\""));
    assertEquals(TodoJsonCodec.DEFAULT_SCHEMA_VERSION, document.schemaVersion());
    assertEquals(1, document.goals().size());
    assertEquals(goal.id(), document.goals().get(0).id());
    assertEquals("agent.exchange", document.goals().get(0).ownerId());
    assertEquals("scan", document.goals().get(0).phases().get(0).tasks().get(0).metadata().get("kind"));
  }

  @Test
  void exposesSchemaAndSupportsOpenGoalExports() {
    TodoManager manager = new TodoManager(new InMemoryTodoStore(), fixedClock());
    TodoAgentFacade facade = new TodoAgentFacade(manager);
    facade.plan(new TodoGoalDraft(
        "agent.alpha",
        "Alpha",
        "",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("Task A", "", null, 0, List.of(), null)
        ), null)),
        null
    ));
    facade.plan(new TodoGoalDraft(
        "agent.alpha",
        "Beta",
        "",
        List.of(new TodoPhaseDraft("Phase", "", List.of(
            new TodoTaskDraft("Task B", "", null, 0, List.of(), null)
        ), null)),
        null
    ));

    String schema = facade.todoPlanJsonSchema();
    TodoPlanDocument exported = facade.importPlanDocument(facade.exportOpenGoalPlansJson("agent.alpha", "service:test"));

    assertNotNull(schema);
    assertTrue(schema.contains("\"const\": \"tutil.todo.plan.v1\""));
    assertEquals(2, exported.goals().size());
    assertEquals("service:test", exported.source());
  }

  private static Clock fixedClock() {
    return Clock.fixed(Instant.parse("2026-05-22T12:00:00Z"), ZoneOffset.UTC);
  }
}
