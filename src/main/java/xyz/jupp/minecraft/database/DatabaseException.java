package xyz.jupp.minecraft.database;

import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;

/** Unchecked wrapper for SQL errors, so the repositories keep plain method signatures. */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message, @Nullable SQLException cause) {
        super(message, cause);
    }

}
