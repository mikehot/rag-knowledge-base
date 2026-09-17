package com.example.ragknowledgebase.document;

public record DocumentContentSnapshot(
    String filename,
    String fileType,
    String filePath,
    String checksum,
    int contentVersion,
    DocumentStatus status,
    int chunkCount,
    String errorMsg
) {
}
