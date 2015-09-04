package com.github.kagkarlsson.scheduler;

import com.github.kagkarlsson.scheduler.task.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.*;

public class JdbcTaskRepositoryTest {

	@Rule
	public HsqlTestDatabaseRule DB = new HsqlTestDatabaseRule();
//	public EmbeddedPostgresqlRule DB = new EmbeddedPostgresqlRule(DbUtils.runSqlResource("/postgresql_tables.sql"), DbUtils::clearTables);

	private JdbcTaskRepository taskRepository;
	private OneTimeTask oneTimeTask;
	private RecurringTask recurringTask;

	@Before
	public void setUp() {
		oneTimeTask = new OneTimeTask("OneTime", instance -> {
		});
		recurringTask = new RecurringTask("RecurringTask", FixedDelay.of(Duration.ofSeconds(1)), TestTasks.DO_NOTHING);
		List<Task> knownTasks = new ArrayList<>();
		knownTasks.add(oneTimeTask);
		knownTasks.add(recurringTask);
		taskRepository = new JdbcTaskRepository(DB.getDataSource(), new TaskResolver(knownTasks, TaskResolver.OnCannotResolve.WARN_ON_UNRESOLVED));
	}

	@Test
	public void test_createIfNotExists() {
		LocalDateTime now = LocalDateTime.now();

		TaskInstance instance1 = oneTimeTask.instance("id1");
		TaskInstance instance2 = oneTimeTask.instance("id2");

		assertTrue(taskRepository.createIfNotExists(new Execution(now, instance1)));
		assertFalse(taskRepository.createIfNotExists(new Execution(now, instance1)));

		assertTrue(taskRepository.createIfNotExists(new Execution(now, instance2)));
	}

	@Test
	public void get_due_should_only_include_due_executions() {
		LocalDateTime now = LocalDateTime.now();

		taskRepository.createIfNotExists(new Execution(now, oneTimeTask.instance("id1")));
		assertThat(taskRepository.getDue(now), hasSize(1));
		assertThat(taskRepository.getDue(now.minusSeconds(1)), hasSize(0));
	}

	@Test
	public void get_due_should_honor_max_results_limit() {
		LocalDateTime now = LocalDateTime.now();

		taskRepository.createIfNotExists(new Execution(now, oneTimeTask.instance("id1")));
		taskRepository.createIfNotExists(new Execution(now, oneTimeTask.instance("id2")));
		assertThat(taskRepository.getDue(now, 1), hasSize(1));
		assertThat(taskRepository.getDue(now, 2), hasSize(2));
	}

	@Test
	public void get_due_should_be_sorted() {
		LocalDateTime now = LocalDateTime.now();
		IntStream.range(0, 100).forEach(i ->
						taskRepository.createIfNotExists(new Execution(now.minusSeconds(new Random().nextInt(10000)), oneTimeTask.instance("id" + i)))
		);
		List<Execution> due = taskRepository.getDue(now);
		assertThat(due, hasSize(100));

		List<Execution> sortedDue = new ArrayList<>(due);
		Collections.sort(sortedDue, Comparator.comparing(Execution::getExecutionTime));
		assertThat(due, is(sortedDue));
	}

	@Test
	public void picked_executions_should_not_be_returned_as_due() {
		LocalDateTime now = LocalDateTime.now();
		taskRepository.createIfNotExists(new Execution(now, oneTimeTask.instance("id1")));
		List<Execution> due = taskRepository.getDue(now);
		assertThat(due, hasSize(1));

		taskRepository.pick(due.get(0));
		assertThat(taskRepository.getDue(now), hasSize(0));
	}

	@Test
	public void reschedule_should_move_execution_in_time() {
		LocalDateTime now = LocalDateTime.now();
		taskRepository.createIfNotExists(new Execution(now, oneTimeTask.instance("id1")));
		List<Execution> due = taskRepository.getDue(now);
		assertThat(due, hasSize(1));

		Execution execution = due.get(0);
		taskRepository.pick(execution);
		taskRepository.reschedule(execution, now.plusMinutes(1));

		assertThat(taskRepository.getDue(now), hasSize(0));
		assertThat(taskRepository.getDue(now.plusMinutes(1)), hasSize(1));
	}


}