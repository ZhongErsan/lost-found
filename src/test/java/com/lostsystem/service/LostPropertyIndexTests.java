package com.lostsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lostsystem.entity.LostPropertyEntity;
import com.lostsystem.mapper.LostPropertyMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LostPropertyIndexTests {
    @TempDir Path directory;
    private final Map<Long, LostPropertyEntity> database = new LinkedHashMap<>();
    private final List<LostPropertyIndexService> services = new ArrayList<>();
    private LostPropertyMapper mapper;
    private EmbeddingModel model;
    private Path file;

    @BeforeEach
    void setUp() {
        file = directory.resolve("index.json");
        mapper = mock(LostPropertyMapper.class);
        model = mock(EmbeddingModel.class);
        when(mapper.findAllForIndex()).thenAnswer(call -> List.copyOf(database.values()));
        when(mapper.findByIdForIndex(anyLong())).thenAnswer(call -> database.get(call.getArgument(0)));
        when(model.embed(anyString())).thenAnswer(call -> Response.from(Embedding.from(
                call.<String>getArgument(0).contains("蓝色") ? new float[]{1, 0} : new float[]{-1, 0})));
    }

    private LostPropertyIndexService open(String version) throws Exception {
        var service = new LostPropertyIndexService(mapper, model, file.toString(), version);
        services.add(service);
        service.initialize();
        return service;
    }

    private LostPropertyEntity row(long id, long owner, String feature) {
        var entity = new LostPropertyEntity();
        entity.setId(id);
        entity.setUserId(owner);
        entity.setUsername("private-contact");
        entity.setPhone("13800000000");
        entity.setLostName("钱包");
        entity.setLostType(feature);
        database.put(id, entity);
        return entity;
    }

    @AfterEach
    void close() throws Exception {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        for (var service : services) service.close();
    }

    @Test
    void persistsAndRestoresWithoutReembeddingUnchangedRecords() throws Exception {
        row(7, 22, "蓝色挂件");
        var first = open("v1");
        first.run(null);
        String disk = Files.readString(file);
        assertFalse(disk.contains("13800000000"));
        assertFalse(disk.contains("private-contact"));
        first.close();
        clearInvocations(model);

        var restored = open("v1");
        restored.run(null);
        verifyNoInteractions(model);
        assertTrue(restored.search("蓝色钱包").get(0).contains("\"id\":7"));
    }

    @Test
    void updateReplacesOldVectorRatherThanAppendingDuplicate() throws Exception {
        var entity = row(7, 22, "红色挂件");
        var index = open("v1");
        index.reconcile();
        entity.setLostType("蓝色挂件");
        index.syncRecord(7L);
        index.syncRecord(7L);
        var disk = new ObjectMapper().readTree(file.toFile());
        assertEquals(1, disk.get("entries").size());
        assertEquals("蓝色挂件", disk.at("/entries/0/clue/lostType").asText());
        assertTrue(index.search("红色钱包").isEmpty());
        assertEquals(1, index.search("蓝色钱包").size());
        verify(model, times(1)).embed("物品名称：钱包\n物品特征：蓝色挂件");
    }

    @Test
    void startupCatchesOfflineChangesAndDeletesWithoutReembeddingOtherRows() throws Exception {
        row(1, 10, "红色挂件");
        row(2, 20, "蓝色挂件");
        var index = open("v1");
        index.reconcile();
        index.close();
        database.remove(1L);
        row(3, 30, "蓝色贴纸");
        clearInvocations(model);
        var restored = open("v1");
        assertEquals(2, restored.reconcile());
        verify(model, times(1)).embed(anyString());
        var disk = new ObjectMapper().readTree(file.toFile());
        assertEquals(2, disk.get("entries").size());
        assertFalse(Files.readString(file).contains("红色挂件"));
    }

    @Test
    void searchesAcrossOwnersButDoesNotExposeContacts() throws Exception {
        row(7, 22, "蓝色挂件");
        row(8, 33, "蓝色贴纸");
        var index = open("v1");
        index.reconcile();
        var results = index.search("蓝色钱包");
        assertEquals(2, results.size());
        assertFalse(results.toString().contains("phone"));
        assertFalse(results.toString().contains("username"));
        verify(mapper, never()).findTop20ByUserIdOrderByIdDesc(anyLong());
        verify(mapper, never()).findAllByUserIdAndPhone(anyLong(), anyString());
    }

    @Test
    void modelFailureKeepsLastCheckpointAndRetryRepairsIt() throws Exception {
        row(7, 22, "红色挂件");
        var index = open("v1");
        index.reconcile();
        String previous = Files.readString(file);
        database.get(7L).setLostType("蓝色挂件");
        when(model.embed("物品名称：钱包\n物品特征：蓝色挂件"))
                .thenThrow(new IllegalStateException("temporary model failure"))
                .thenReturn(Response.from(Embedding.from(new float[]{1, 0})));
        assertFalse(index.syncAfterCommit(7L));
        assertEquals(previous, Files.readString(file));
        assertTrue(index.search("红色钱包").isEmpty(), "Old description must not appear as a current clue");
        index.retrySynchronization();
        assertEquals(1, index.search("蓝色钱包").size());
    }

    @Test
    void diskFailureDoesNotPublishUnpersistedSnapshotAndCanBeRetried() throws Exception {
        var index = open("v1");
        index.reconcile();
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "cannot replace directory");
        row(7, 22, "蓝色挂件");
        assertFalse(index.syncAfterCommit(7L));
        assertTrue(index.search("蓝色钱包").isEmpty());
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        index.retrySynchronization();
        assertEquals(1, index.search("蓝色钱包").size());
    }

    @Test
    void transactionSchedulesIndexUpdateOnlyAfterCommit() throws Exception {
        var index = open("v1");
        row(7, 22, "蓝色挂件");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        assertFalse(index.syncAfterCommit(7L));
        verifyNoInteractions(model);
        assertFalse(Files.exists(file));
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
        callbacks.forEach(TransactionSynchronization::afterCommit);
        assertTrue(Files.exists(file));
        assertEquals(1, index.search("蓝色钱包").size());
    }

    @Test
    void rollbackDoesNotWriteAnyVector() throws Exception {
        var index = open("v1");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        index.syncAfterCommit(7L);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verifyNoInteractions(mapper, model);
        assertFalse(Files.exists(file));
    }

    @Test
    void deletedRowsAreSuppressedImmediatelyAndRemovedOnReconciliation() throws Exception {
        row(7, 22, "蓝色挂件");
        var index = open("v1");
        index.reconcile();
        database.remove(7L);
        assertTrue(index.search("蓝色钱包").isEmpty());
        assertEquals(0, index.reconcile());
        assertEquals(0, new ObjectMapper().readTree(file.toFile()).get("entries").size());
    }

    @Test
    void modelVersionChangeRebuildsVectors() throws Exception {
        row(7, 22, "蓝色挂件");
        var first = open("v1");
        first.reconcile();
        first.close();
        clearInvocations(model);
        var updated = open("v2");
        updated.reconcile();
        verify(model, times(1)).embed(anyString());
        assertEquals("v2", new ObjectMapper().readTree(file.toFile()).get("modelVersion").asText());
    }

    @Test
    void corruptIndexFailsWithoutOverwritingIt() throws Exception {
        Files.writeString(file, "broken checkpoint");
        assertThrows(java.io.IOException.class, () -> open("v1"));
        assertEquals("broken checkpoint", Files.readString(file));
    }

    @Test
    void rejectsTwoInstancesSharingOneIndex() throws Exception {
        open("v1");
        assertThrows(Exception.class, () -> open("v1"));
    }
}
