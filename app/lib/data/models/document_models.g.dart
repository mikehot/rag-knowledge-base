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
