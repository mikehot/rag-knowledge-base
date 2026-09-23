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

@JsonSerializable()
class DocumentAclItem {
  const DocumentAclItem({
    required this.id,
    required this.documentId,
    required this.principalType,
    required this.principalId,
    required this.permission,
  });

  final String id;
  final String documentId;
  final String principalType;
  final String principalId;
  final String permission;

  factory DocumentAclItem.fromJson(Map<String, dynamic> json) =>
      _$DocumentAclItemFromJson(json);
}

@JsonSerializable()
class GrantDocumentAclRequest {
  const GrantDocumentAclRequest({
    required this.principalType,
    required this.principalId,
    required this.permission,
  });

  final String principalType;
  final String principalId;
  final String permission;

  Map<String, dynamic> toJson() => _$GrantDocumentAclRequestToJson(this);
}

@JsonSerializable()
class DeleteDocumentAclResponse {
  const DeleteDocumentAclResponse({required this.deleted});

  final bool deleted;

  factory DeleteDocumentAclResponse.fromJson(Map<String, dynamic> json) =>
      _$DeleteDocumentAclResponseFromJson(json);
}

@JsonSerializable()
class PrincipalOption {
  const PrincipalOption({
    required this.id,
    this.username,
    this.displayName,
    this.name,
    this.code,
    this.status,
  });

  final String id;
  final String? username;
  final String? displayName;
  final String? name;
  final String? code;
  final String? status;

  String get label => switch ((displayName, name, code, username)) {
    (final value?, _, _, _) when value.trim().isNotEmpty => value,
    (_, final value?, _, _) when value.trim().isNotEmpty => value,
    (_, _, final value?, _) when value.trim().isNotEmpty => value,
    (_, _, _, final value?) => value,
    _ => id,
  };

  factory PrincipalOption.fromJson(Map<String, dynamic> json) =>
      _$PrincipalOptionFromJson(json);
}

@JsonSerializable()
class IndexTaskListResponse {
  const IndexTaskListResponse({
    required this.items,
    required this.limit,
    required this.hasMore,
  });

  final List<IndexTaskItem> items;
  final int limit;
  final bool hasMore;

  factory IndexTaskListResponse.fromJson(Map<String, dynamic> json) =>
      _$IndexTaskListResponseFromJson(json);
}

@JsonSerializable()
class IndexTaskItem {
  const IndexTaskItem({
    required this.id,
    required this.knowledgeBaseId,
    required this.documentId,
    required this.operation,
    required this.contentVersion,
    required this.status,
    required this.attemptCount,
    required this.maxAttempts,
    this.errorMessage,
    this.nextAttemptAt,
    this.startedAt,
    this.finishedAt,
    this.durationMs,
    this.createdAt,
  });

  final String id;
  final String knowledgeBaseId;
  final String documentId;
  final String operation;
  final int contentVersion;
  final String status;
  final int attemptCount;
  final int maxAttempts;
  final String? errorMessage;
  final DateTime? nextAttemptAt;
  final DateTime? startedAt;
  final DateTime? finishedAt;
  final int? durationMs;
  final DateTime? createdAt;

  factory IndexTaskItem.fromJson(Map<String, dynamic> json) =>
      _$IndexTaskItemFromJson(json);
}
