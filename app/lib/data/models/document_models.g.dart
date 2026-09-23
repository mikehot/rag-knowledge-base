// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'document_models.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

DocumentListResponse _$DocumentListResponseFromJson(
  Map<String, dynamic> json,
) => DocumentListResponse(
  items: (json['items'] as List<dynamic>)
      .map((e) => DocumentItem.fromJson(e as Map<String, dynamic>))
      .toList(),
);

Map<String, dynamic> _$DocumentListResponseToJson(
  DocumentListResponse instance,
) => <String, dynamic>{'items': instance.items};

DocumentItem _$DocumentItemFromJson(Map<String, dynamic> json) => DocumentItem(
  documentId: json['documentId'] as String,
  filename: json['filename'] as String,
  fileType: json['fileType'] as String?,
  status: json['status'] as String,
  chunkCount: (json['chunkCount'] as num).toInt(),
  errorMsg: json['errorMsg'] as String?,
  createdAt: json['createdAt'] == null
      ? null
      : DateTime.parse(json['createdAt'] as String),
  disabled: json['disabled'] as bool? ?? false,
);

Map<String, dynamic> _$DocumentItemToJson(DocumentItem instance) =>
    <String, dynamic>{
      'documentId': instance.documentId,
      'filename': instance.filename,
      'fileType': instance.fileType,
      'status': instance.status,
      'chunkCount': instance.chunkCount,
      'errorMsg': instance.errorMsg,
      'createdAt': instance.createdAt?.toIso8601String(),
      'disabled': instance.disabled,
    };

DocumentUploadResponse _$DocumentUploadResponseFromJson(
  Map<String, dynamic> json,
) => DocumentUploadResponse(
  documentId: json['documentId'] as String,
  status: json['status'] as String,
);

Map<String, dynamic> _$DocumentUploadResponseToJson(
  DocumentUploadResponse instance,
) => <String, dynamic>{
  'documentId': instance.documentId,
  'status': instance.status,
};

DeleteDocumentResponse _$DeleteDocumentResponseFromJson(
  Map<String, dynamic> json,
) => DeleteDocumentResponse(deleted: json['deleted'] as bool);

Map<String, dynamic> _$DeleteDocumentResponseToJson(
  DeleteDocumentResponse instance,
) => <String, dynamic>{'deleted': instance.deleted};

DocumentLifecycleResponse _$DocumentLifecycleResponseFromJson(
  Map<String, dynamic> json,
) => DocumentLifecycleResponse(
  documentId: json['documentId'] as String,
  status: json['status'] as String,
  contentVersion: (json['contentVersion'] as num).toInt(),
  permissionVersion: (json['permissionVersion'] as num).toInt(),
  disabled: json['disabled'] as bool,
  deleted: json['deleted'] as bool,
  taskId: json['taskId'] as String?,
);

Map<String, dynamic> _$DocumentLifecycleResponseToJson(
  DocumentLifecycleResponse instance,
) => <String, dynamic>{
  'documentId': instance.documentId,
  'status': instance.status,
  'contentVersion': instance.contentVersion,
  'permissionVersion': instance.permissionVersion,
  'disabled': instance.disabled,
  'deleted': instance.deleted,
  'taskId': instance.taskId,
};

DocumentAclItem _$DocumentAclItemFromJson(Map<String, dynamic> json) =>
    DocumentAclItem(
      id: json['id'] as String,
      documentId: json['documentId'] as String,
      principalType: json['principalType'] as String,
      principalId: json['principalId'] as String,
      permission: json['permission'] as String,
    );

Map<String, dynamic> _$DocumentAclItemToJson(DocumentAclItem instance) =>
    <String, dynamic>{
      'id': instance.id,
      'documentId': instance.documentId,
      'principalType': instance.principalType,
      'principalId': instance.principalId,
      'permission': instance.permission,
    };

GrantDocumentAclRequest _$GrantDocumentAclRequestFromJson(
  Map<String, dynamic> json,
) => GrantDocumentAclRequest(
  principalType: json['principalType'] as String,
  principalId: json['principalId'] as String,
  permission: json['permission'] as String,
);

Map<String, dynamic> _$GrantDocumentAclRequestToJson(
  GrantDocumentAclRequest instance,
) => <String, dynamic>{
  'principalType': instance.principalType,
  'principalId': instance.principalId,
  'permission': instance.permission,
};

DeleteDocumentAclResponse _$DeleteDocumentAclResponseFromJson(
  Map<String, dynamic> json,
) => DeleteDocumentAclResponse(deleted: json['deleted'] as bool);

Map<String, dynamic> _$DeleteDocumentAclResponseToJson(
  DeleteDocumentAclResponse instance,
) => <String, dynamic>{'deleted': instance.deleted};

PrincipalOption _$PrincipalOptionFromJson(Map<String, dynamic> json) =>
    PrincipalOption(
      id: json['id'] as String,
      username: json['username'] as String?,
      displayName: json['displayName'] as String?,
      name: json['name'] as String?,
      code: json['code'] as String?,
      status: json['status'] as String?,
    );

Map<String, dynamic> _$PrincipalOptionToJson(PrincipalOption instance) =>
    <String, dynamic>{
      'id': instance.id,
      'username': instance.username,
      'displayName': instance.displayName,
      'name': instance.name,
      'code': instance.code,
      'status': instance.status,
    };

IndexTaskListResponse _$IndexTaskListResponseFromJson(
  Map<String, dynamic> json,
) => IndexTaskListResponse(
  items: (json['items'] as List<dynamic>)
      .map((e) => IndexTaskItem.fromJson(e as Map<String, dynamic>))
      .toList(),
  limit: (json['limit'] as num).toInt(),
  hasMore: json['hasMore'] as bool,
);

Map<String, dynamic> _$IndexTaskListResponseToJson(
  IndexTaskListResponse instance,
) => <String, dynamic>{
  'items': instance.items,
  'limit': instance.limit,
  'hasMore': instance.hasMore,
};

IndexTaskItem _$IndexTaskItemFromJson(Map<String, dynamic> json) =>
    IndexTaskItem(
      id: json['id'] as String,
      knowledgeBaseId: json['knowledgeBaseId'] as String,
      documentId: json['documentId'] as String,
      operation: json['operation'] as String,
      contentVersion: (json['contentVersion'] as num).toInt(),
      status: json['status'] as String,
      attemptCount: (json['attemptCount'] as num).toInt(),
      maxAttempts: (json['maxAttempts'] as num).toInt(),
      errorMessage: json['errorMessage'] as String?,
      nextAttemptAt: json['nextAttemptAt'] == null
          ? null
          : DateTime.parse(json['nextAttemptAt'] as String),
      startedAt: json['startedAt'] == null
          ? null
          : DateTime.parse(json['startedAt'] as String),
      finishedAt: json['finishedAt'] == null
          ? null
          : DateTime.parse(json['finishedAt'] as String),
      durationMs: (json['durationMs'] as num?)?.toInt(),
      createdAt: json['createdAt'] == null
          ? null
          : DateTime.parse(json['createdAt'] as String),
    );

Map<String, dynamic> _$IndexTaskItemToJson(IndexTaskItem instance) =>
    <String, dynamic>{
      'id': instance.id,
      'knowledgeBaseId': instance.knowledgeBaseId,
      'documentId': instance.documentId,
      'operation': instance.operation,
      'contentVersion': instance.contentVersion,
      'status': instance.status,
      'attemptCount': instance.attemptCount,
      'maxAttempts': instance.maxAttempts,
      'errorMessage': instance.errorMessage,
      'nextAttemptAt': instance.nextAttemptAt?.toIso8601String(),
      'startedAt': instance.startedAt?.toIso8601String(),
      'finishedAt': instance.finishedAt?.toIso8601String(),
      'durationMs': instance.durationMs,
      'createdAt': instance.createdAt?.toIso8601String(),
    };
