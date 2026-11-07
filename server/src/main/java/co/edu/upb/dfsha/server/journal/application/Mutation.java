package co.edu.upb.dfsha.server.journal.application;

import co.edu.upb.dfsha.server.journal.op.JournalOp;

public record Mutation<T>(String type, JournalOp operation, T result) {}
