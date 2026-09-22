import 'package:json_annotation/json_annotation.dart';

part 'document_models.g.dart';

@JsonSerializable()
class DocumentListResponse {
  const DocumentListResponse({required this.items});

  final List<DocumentItem> items;

  factory DocumentListResponse.fromJson(Map<String, dynamic> json) =>
      _$DocumentListResponseFromJson(json);
}

@JsonSerializable()
class DocumentItem {
  const DocumentItem({
    required this.documentId,
    required this.filename,
    required this.fileType,
    required this.status,
    required this.chunkCount,
    this.errorMsg,
    this.createdAt,
    this.disabled = false,
  });

  final String documentId;
  final String filename;
  final String? fileType;
  final String status;
  final int chunkCount;
  final String? errorMsg;
  final DateTime? createdAt;
  final bool disabled;

  DocumentStatus get statusEnum => switch (status) {
    _ when disabled => DocumentStatus.disabled,
    'ready' => DocumentStatus.ready,
    'failed' => DocumentStatus.failed,
    _ => DocumentStatus.processing,
  };

  factory DocumentItem.fromJson(Map<String, dynamic> json) =>
      _$DocumentItemFromJson(json);
}

enum DocumentStatus { processing, ready, failed, disabled }

@JsonSerializable()
class DocumentUploadResponse {
  const DocumentUploadResponse({
    required this.documentId,
    required this.status,
  });

  final String documentId;
  final String status;

  factory DocumentUploadResponse.fromJson(Map<String, dynamic> json) =>
      _$DocumentUploadResponseFromJson(json);
}

@JsonSerializable()
class DeleteDocumentResponse {
  const DeleteDocumentResponse({required this.deleted});

  final bool deleted;

  factory DeleteDocumentResponse.fromJson(Map<String, dynamic> json) =>
      _$DeleteDocumentResponseFromJson(json);
}

@JsonSerializable()
class DocumentLifecycleResponse {
  const DocumentLifecycleResponse({
    required this.documentId,
    required this.status,
    required this.contentVersion,
    required this.permissionVersion,
    required this.disabled,
    required this.deleted,
    this.taskId,
  });

  final String documentId;
  final String status;
  final int contentVersion;
  final int permissionVersion;
  final bool disabled;
  final bool deleted;
  final String? taskId;

  factory DocumentLifecycleResponse.fromJson(Map<String, dynamic> json) =>
      _$DocumentLifecycleResponseFromJson(json);
}
