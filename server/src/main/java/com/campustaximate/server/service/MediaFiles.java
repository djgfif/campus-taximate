package com.campustaximate.server.service;

import com.campustaximate.server.common.AppClock;
import com.campustaximate.server.domain.FileDeleteJob;
import com.campustaximate.server.repository.FileDeleteJobRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class MediaFiles {
    private final FileDeleteJobRepository jobs;
    private final AppClock clock;
    private final Path root;
    private final TransactionTemplate transaction;
    private final TransactionTemplate outsideTransaction;
    private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean(false);

    public MediaFiles(FileDeleteJobRepository jobs, AppClock clock, PlatformTransactionManager manager,
                      @Value("${app.upload-dir}") String uploadDir) {
        this.jobs = jobs;
        this.clock = clock;
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outsideTransaction = new TransactionTemplate(manager);
        outsideTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    String storeReceipt(MultipartFile file) {
        return store(file, root);
    }

    String storeChatImage(long partyId, MultipartFile file) {
        return store(file, root.resolve("chat").resolve(Long.toString(partyId)));
    }

    private String store(MultipartFile file, Path directory) {
        requireTransaction();
        Path target = directory.resolve(UUID.randomUUID().toString());
        try {
            Files.createDirectories(root);
            // Do not follow a substituted child directory outside the upload tree.
            Path current = root;
            if (!root.equals(directory)) {
                for (Path child : root.relativize(directory)) {
                    current = current.resolve(child);
                    if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(current);
                    checkedParent(root, current);
                }
            }
            String filename = target.toString();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) cleanupRollback(filename);
                }
            });
            file.transferTo(target);
            return filename;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Called in the transaction that detaches the file. Until it commits, keep the file intact. */
    void deleteAfterCommit(String path) {
        if (path == null) return;
        requireTransaction();
        jobs.save(new FileDeleteJob(path, clock.now().truncatedTo(ChronoUnit.MICROS)));
        if (TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(CommitDrain.class::isInstance)) {
            TransactionSynchronizationManager.registerSynchronization(new CommitDrain());
        }
    }

    private class CommitDrain implements TransactionSynchronization {
        @Override public void afterCommit() { tryDrain(); }
    }

    private void cleanupRollback(String path) {
        try {
            transaction.executeWithoutResult(status -> jobs.save(new FileDeleteJob(path, clock.now().truncatedTo(ChronoUnit.MICROS))));
            tryDrain();
        } catch (RuntimeException databaseFailure) {
            log.warn("Could not schedule cleanup for a rolled-back upload: {}", databaseFailure.getClass().getSimpleName());
            try { delete(path); }
            catch (IOException | RuntimeException storageFailure) {
                log.warn("Could not remove a rolled-back upload: {}", storageFailure.getClass().getSimpleName());
            }
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Upload changes require an active transaction");
        }
    }

    /** One bounded batch. Replaying a job after a crash is safe even if the file is already gone. */
    @Scheduled(fixedDelay = 30000)
    public void retryDue() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            outsideTransaction.executeWithoutResult(status -> {
                for (FileDeleteJob job : jobs.findTop40ByNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(clock.now())) {
                    try {
                        delete(job.getPath());
                    } catch (IOException | RuntimeException failure) {
                        long delay = Math.min(3600, 30L << Math.min(job.getAttempts(), 7));
                        log.warn("Upload deletion pending: job={}, failure={}", job.getId(), failure.getClass().getSimpleName());
                        transaction.executeWithoutResult(tx -> jobs.defer(job.getId(), clock.now().plusSeconds(delay).truncatedTo(ChronoUnit.MICROS)));
                        continue;
                    }
                    transaction.executeWithoutResult(tx -> jobs.deleteById(job.getId()));
                }
            });
        } finally {
            draining.set(false);
        }
    }

    private void tryDrain() {
        try { retryDue(); }
        catch (RuntimeException failure) {
            // The outbox has committed; do not turn an accepted user action into an error.
            log.warn("Upload deletion batch deferred: {}", failure.getClass().getSimpleName());
        }
    }

    boolean readable(String value) {
        if (value == null) return false;
        try {
            Path path = checkedFile(value);
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path);
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private void delete(String value) throws IOException {
        try {
            Path path = checkedFile(value);
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Expected an upload file");
            Files.deleteIfExists(path);
        } catch (NoSuchFileException ignored) {
            // A previous attempt completed before the job could be acknowledged.
        }
    }

    private Path checkedFile(String value) throws IOException {
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Upload outside upload directory");
        checkedParent(root, path.getParent());
        return path;
    }

    private static void checkedParent(Path root, Path parent) throws IOException {
        if (!parent.toRealPath().startsWith(root.toRealPath())) throw new IOException("Upload directory outside upload root");
    }
}
