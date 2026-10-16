package za.co.shapeitsolutions.portfolio.resilientpay.simulator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Persists the synthetic effect before publication so a broker redelivery after process restart
 * reuses the same outcome body and message identity instead of executing the attempt again.
 *
 * <p>The journal is deliberately single-writer and local to the simulator. Its directory must be
 * backed by a durable volume; PostgreSQL remains authoritative for the orchestrator workflow.
 *
 * @author Daniel Jr Nkulu
 */
@Component
public final class SimulatorExecutionJournal {
    private static final String FORMAT_VERSION = "1";
    private static final String INTERRUPTED = "INTERRUPTED";
    private static final String OUTCOME = "OUTCOME";
    private static final long MAX_ENTRY_BYTES = 1024;

    private final Path directory;
    private final Runnable postPersistHook;

    /** Creates and validates the configured durable journal directory. */
    @Autowired
    public SimulatorExecutionJournal(SimulatorProperties properties) {
        this(properties, () -> {});
    }

    SimulatorExecutionJournal(SimulatorProperties properties, Runnable postPersistHook) {
        this.directory = Objects.requireNonNull(properties, "properties must not be null")
                .journalDirectory()
                .toAbsolutePath()
                .normalize();
        this.postPersistHook = Objects.requireNonNull(postPersistHook, "postPersistHook must not be null");
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory) || !Files.isWritable(directory)) {
                throw new IllegalStateException("Simulator journal directory must be writable");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Simulator journal directory could not be initialised", exception);
        }
    }

    /**
     * Resolves one attempt under an operating-system file lock. A deterministic outcome intent is
     * atomically persisted before it can leave this boundary for publication.
     *
     * @param request validated execution request
     * @param interruptFirst whether the first delivery must persist an interruption marker
     * @param outcome deterministic candidate outcome; ignored when a durable outcome exists
     * @return durable result, either newly created or replayed
     */
    public synchronized ProcessedExecution resolve(
            ExecutionRequestedPayload request, boolean interruptFirst, OutcomePayload outcome) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Path journal = journalPath(request.executionAttemptId());
        Path lockPath = lockPath(request.executionAttemptId());
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = lockChannel.lock()) {
            JournalEntry existing = read(journal, request);
            if (existing != null && existing.result() != null) {
                return existing.result();
            }
            if (existing == null && interruptFirst) {
                persist(journal, interruptedLines(request));
                throw new SyntheticInterruptionException();
            }
            requireMatchingRequest(request, outcome);
            ProcessedExecution processed = new ProcessedExecution(UUID.randomUUID(), outcome);
            persist(journal, outcomeLines(processed));
            postPersistHook.run();
            return processed;
        } catch (SyntheticInterruptionException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Simulator execution journal operation failed", exception);
        }
    }

    private JournalEntry read(Path journal, ExecutionRequestedPayload request) throws IOException {
        if (!Files.exists(journal)) {
            return null;
        }
        if (!Files.isRegularFile(journal) || Files.isSymbolicLink(journal) || Files.size(journal) > MAX_ENTRY_BYTES) {
            throw new IllegalStateException("Simulator journal entry is not a regular file");
        }
        List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
        if (lines.size() < 4 || !FORMAT_VERSION.equals(lines.get(0))) {
            throw new IllegalStateException("Simulator journal entry has an unsupported format");
        }
        UUID reversalCommandId = UUID.fromString(lines.get(2));
        UUID executionAttemptId = UUID.fromString(lines.get(3));
        if (!request.reversalCommandId().equals(reversalCommandId)
                || !request.executionAttemptId().equals(executionAttemptId)) {
            throw new IllegalStateException("Simulator journal identity does not match the request");
        }
        if (INTERRUPTED.equals(lines.get(1)) && lines.size() == 4) {
            return new JournalEntry(null);
        }
        if (!OUTCOME.equals(lines.get(1)) || lines.size() != 8) {
            throw new IllegalStateException("Simulator journal entry has an invalid state");
        }
        String reasonCode = lines.get(6).isEmpty() ? null : lines.get(6);
        if (reasonCode != null && !reasonCode.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalStateException("Simulator journal reason code is invalid");
        }
        OutcomePayload outcome = new OutcomePayload(
                reversalCommandId,
                executionAttemptId,
                SwitchOutcome.valueOf(lines.get(5)),
                reasonCode,
                Instant.parse(lines.get(7)));
        return new JournalEntry(new ProcessedExecution(UUID.fromString(lines.get(4)), outcome));
    }

    private void persist(Path journal, List<String> lines) throws IOException {
        String journalFileName = Objects.requireNonNull(journal.getFileName(), "journal file name must exist")
                .toString();
        Path temporary = Files.createTempFile(directory, journalFileName, ".tmp");
        try {
            byte[] contents = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel =
                    FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(contents);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, journal, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Simulator journal storage must support atomic file replacement", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path journalPath(UUID executionAttemptId) {
        return directory.resolve(executionAttemptId + ".journal");
    }

    private Path lockPath(UUID executionAttemptId) {
        return directory.resolve(executionAttemptId + ".lock");
    }

    private static List<String> interruptedLines(ExecutionRequestedPayload request) {
        return List.of(
                FORMAT_VERSION,
                INTERRUPTED,
                request.reversalCommandId().toString(),
                request.executionAttemptId().toString());
    }

    private static List<String> outcomeLines(ProcessedExecution processed) {
        OutcomePayload outcome = processed.outcome();
        return List.of(
                FORMAT_VERSION,
                OUTCOME,
                outcome.reversalCommandId().toString(),
                outcome.executionAttemptId().toString(),
                processed.outcomeMessageId().toString(),
                outcome.outcome().name(),
                Objects.toString(outcome.reasonCode(), ""),
                outcome.observedAt().toString());
    }

    private static void requireMatchingRequest(ExecutionRequestedPayload request, OutcomePayload outcome) {
        if (!request.reversalCommandId().equals(outcome.reversalCommandId())
                || !request.executionAttemptId().equals(outcome.executionAttemptId())) {
            throw new IllegalArgumentException("Synthetic outcome identity must match the request");
        }
    }

    /**
     * Distinguishes an interruption marker from a fully determined outcome.
     *
     * @param result durable outcome, or null for an interruption marker
     * @author Daniel Jr Nkulu
     */
    private record JournalEntry(ProcessedExecution result) {}
}
