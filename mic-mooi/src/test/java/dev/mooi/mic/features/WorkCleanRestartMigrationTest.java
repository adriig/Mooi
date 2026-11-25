package dev.mooi.mic.features;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class WorkCleanRestartMigrationTest {
    @Test
    void additiveMigrationRunsTwiceAndPreservesLegacyData() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:restart;MODE=PostgreSQL");
             var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE work_tasks (id integer PRIMARY KEY, attempts integer, report varchar)");
            sql.execute("CREATE TABLE work_task_recovery (task_id integer, snapshot_revision varchar)");
            sql.execute("INSERT INTO work_tasks VALUES (1, 4, 'attention: invalid recovery')");
            sql.execute("INSERT INTO work_task_recovery VALUES (1, 'original-evidence')");
            try (var liquibase = new Liquibase("db/changelog/changes/064-work-clean-restart.yaml",
                    new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
                liquibase.update(new Contexts(), new LabelExpression());
                liquibase.update(new Contexts(), new LabelExpression());
                try (var row = sql.executeQuery("SELECT * FROM work_tasks")) {
                    assertTrue(row.next());
                    assertEquals(4, row.getInt("attempts"));
                    assertEquals("attention: invalid recovery", row.getString("report"));
                    assertNull(row.getString("recovery_disposition"));
                    assertNull(row.getObject("discarded_attempt"));
                }
                try (var row = sql.executeQuery("SELECT snapshot_revision FROM work_task_recovery")) {
                    assertTrue(row.next());
                    assertEquals("original-evidence", row.getString(1));
                }
            }
        }
    }
}
