package com.taxagent.harness;

import com.taxagent.harness.tool.*;
import com.taxagent.harness.tool.PermissionGuard.*;
import java.nio.file.Path;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static com.taxagent.harness.ToolContractTest.fails;
import static org.assertj.core.api.Assertions.*;

class PermissionGuardTest {
    @TempDir Path directory;
    Connection connection;
    final PermissionGuard guard = new PermissionGuard();

    @BeforeEach void initialize() throws Exception {
        var database = directory.resolve("permissions.db");
        DataInitializer.initialize(database);
        connection = DriverManager.getConnection("jdbc:sqlite:" + database);
    }
    @AfterEach void close() throws Exception { if (connection != null) connection.close(); }

    @ParameterizedTest
    @CsvSource({"U001,C001,READ,true", "U001,C001,WRITE,true", "U001,C002,READ,true",
            "U001,C002,WRITE,true", "U002,C001,READ,true", "U002,C001,WRITE,false",
            "U002,C002,READ,false", "U002,C002,WRITE,false", "UNKNOWN,C001,READ,false",
            "U001,UNKNOWN,READ,false", "U001,C001,MANAGE_RULES,false", "U002,C001,MANAGE_RULES,false",
            "U001,'C001'' OR 1=1 --',READ,false"})
    void permissionMatrix(String user, String enterprise, Access access, boolean allowed) {
        Runnable check = () -> guard.require(connection, new Session(user), enterprise, access);
        if (allowed) assertThatCode(check::run).doesNotThrowAnyException();
        else fails(ErrorCode.PERMISSION_DENIED, check);
    }

    @Test void missingSessionAndInvalidParameters() {
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> guard.require(connection, null, "C001", Access.READ));
        fails(ErrorCode.AUTHENTICATION_REQUIRED, () -> new Session(" "));
        fails(ErrorCode.INVALID_ARGUMENT, () -> guard.require(connection, new Session("U001"), "", Access.READ));
        fails(ErrorCode.INVALID_ARGUMENT, () -> guard.require(connection, new Session("U001"), "C001", null));
    }

    @Test void readsCurrentPermissionsAndDeniesMissingRows() throws Exception {
        try (var s = connection.createStatement()) {
            s.executeUpdate("UPDATE permissions SET permission='仅查看' WHERE user_id='U001' AND enterprise_id='C001'");
            fails(ErrorCode.PERMISSION_DENIED, () -> guard.require(connection, new Session("U001"), "C001", Access.WRITE));
            s.executeUpdate("DELETE FROM permissions WHERE user_id='U001' AND enterprise_id='C001'");
            fails(ErrorCode.PERMISSION_DENIED, () -> guard.require(connection, new Session("U001"), "C001", Access.READ));
        }
    }

    @Test void storageFailureIsDistinctAndRetainsCause() throws Exception {
        connection.close();
        assertThatThrownBy(() -> guard.require(connection, new Session("U001"), "C001", Access.READ))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.STORAGE_ERROR);
                    assertThat(e.getCause()).isInstanceOf(SQLException.class);
                    assertThat(e.getMessage()).isEqualTo("Permission lookup failed");
                });
    }
}
