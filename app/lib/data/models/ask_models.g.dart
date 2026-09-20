// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'ask_models.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

AskRequest _$AskRequestFromJson(Map<String, dynamic> json) =>
    AskRequest(question: json['question'] as String);

Map<String, dynamic> _$AskRequestToJson(AskRequest instance) =>
    <String, dynamic>{'question': instance.question};

AskAnswer _$AskAnswerFromJson(Map<String, dynamic> json) => AskAnswer(
  answer: json['answer'] as String,
  found: json['found'] as bool,
  sources: (json['sources'] as List<dynamic>)
      .map((e) => ChunkSource.fromJson(e as Map<String, dynamic>))
      .toList(),
  tokenUsage: (json['tokenUsage'] as num).toInt(),
  grounded: json['grounded'] as bool? ?? false,
);

Map<String, dynamic> _$AskAnswerToJson(AskAnswer instance) => <String, dynamic>{
  'answer': instance.answer,
  'found': instance.found,
  'grounded': instance.grounded,
  'sources': instance.sources,
  'tokenUsage': instance.tokenUsage,
};

ChunkSource _$ChunkSourceFromJson(Map<String, dynamic> json) => ChunkSource(
  documentId: json['documentId'] as String,
  filename: json['filename'] as String,
  locator: json['locator'] as String,
  snippet: json['snippet'] as String,
);

Map<String, dynamic> _$ChunkSourceToJson(ChunkSource instance) =>
    <String, dynamic>{
      'documentId': instance.documentId,
      'filename': instance.filename,
      'locator': instance.locator,
      'snippet': instance.snippet,
    };
