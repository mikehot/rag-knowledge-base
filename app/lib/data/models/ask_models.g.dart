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
  requestId: json['requestId'] as String?,
  latencyMs: (json['latencyMs'] as num?)?.toInt() ?? 0,
  failureReason: json['failureReason'] as String?,
  timings: json['timings'] == null
      ? null
      : AskTimings.fromJson(json['timings'] as Map<String, dynamic>),
);

Map<String, dynamic> _$AskAnswerToJson(AskAnswer instance) => <String, dynamic>{
  'answer': instance.answer,
  'found': instance.found,
  'grounded': instance.grounded,
  'sources': instance.sources,
  'tokenUsage': instance.tokenUsage,
  'requestId': instance.requestId,
  'latencyMs': instance.latencyMs,
  'failureReason': instance.failureReason,
  'timings': instance.timings,
};

AskTimings _$AskTimingsFromJson(Map<String, dynamic> json) => AskTimings(
  embeddingMs: (json['embeddingMs'] as num).toInt(),
  retrievalMs: (json['retrievalMs'] as num).toInt(),
  generationMs: (json['generationMs'] as num).toInt(),
);

Map<String, dynamic> _$AskTimingsToJson(AskTimings instance) =>
    <String, dynamic>{
      'embeddingMs': instance.embeddingMs,
      'retrievalMs': instance.retrievalMs,
      'generationMs': instance.generationMs,
    };

AskFeedbackRequest _$AskFeedbackRequestFromJson(Map<String, dynamic> json) =>
    AskFeedbackRequest(
      rating: json['rating'] as String,
      reason: json['reason'] as String?,
    );

Map<String, dynamic> _$AskFeedbackRequestToJson(AskFeedbackRequest instance) =>
    <String, dynamic>{'rating': instance.rating, 'reason': instance.reason};

AskFeedbackResponse _$AskFeedbackResponseFromJson(Map<String, dynamic> json) =>
    AskFeedbackResponse(
      requestId: json['requestId'] as String,
      rating: json['rating'] as String,
      reason: json['reason'] as String?,
      updatedAt: json['updatedAt'] as String?,
    );

Map<String, dynamic> _$AskFeedbackResponseToJson(
  AskFeedbackResponse instance,
) => <String, dynamic>{
  'requestId': instance.requestId,
  'rating': instance.rating,
  'reason': instance.reason,
  'updatedAt': instance.updatedAt,
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
