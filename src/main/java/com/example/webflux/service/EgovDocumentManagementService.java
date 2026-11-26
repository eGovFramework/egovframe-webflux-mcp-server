package com.example.webflux.service;

import java.util.concurrent.CompletableFuture;

import com.example.webflux.dto.DocumentStatusResponse;

public interface EgovDocumentManagementService {

    CompletableFuture<Integer> loadDocumentsAsync();

    boolean isProcessing();

    DocumentStatusResponse getStatusResponse();

    String reindexDocuments();

}
