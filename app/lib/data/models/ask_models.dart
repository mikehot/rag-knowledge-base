import 'package:json_annotation/json_annotation.dart';

part 'ask_models.g.dart';

@JsonSerializable()
class AskRequest {
  const AskRequest({required this.question});

  final String question;

  Map<String, dynamic> toJson() => _$AskRequestToJson(this);
}

@JsonSerializable()
class AskAnswer {
  const AskAnswer({
    required this.answer,
    required this.found,
    required this.sources,
    required this.tokenUsage,
    this.grounded = false,
    this.requestId,
    this.latencyMs = 0,
    this.failureReason,
    this.timings,
  });

  final String answer;
  final bool found;
  final bool grounded;
  final List<ChunkSource> sources;
  final int tokenUsage;
  final String? requestId;
  final int latencyMs;
  final String? failureReason;
  final AskTimings? timings;

  factory AskAnswer.fromJson(Map<String, dynamic> json) =>
      _$AskAnswerFromJson(json);
}

@JsonSerializable()
class AskTimings {
  const AskTimings({
    required this.embeddingMs,
    required this.retrievalMs,
    required this.generationMs,
  });

  final int embeddingMs;
  final int retrievalMs;
  final int generationMs;

  factory AskTimings.fromJson(Map<String, dynamic> json) =>
      _$AskTimingsFromJson(json);
}

@JsonSerializable()
class AskFeedbackRequest {
  const AskFeedbackRequest({required this.rating, this.reason});

  final String rating;
  final String? reason;

  Map<String, dynamic> toJson() => _$AskFeedbackRequestToJson(this);
}

@JsonSerializable()
class AskFeedbackResponse {
  const AskFeedbackResponse({
    required this.requestId,
    required this.rating,
    this.reason,
    this.updatedAt,
  });

  final String requestId;
  final String rating;
  final String? reason;
  final String? updatedAt;

  factory AskFeedbackResponse.fromJson(Map<String, dynamic> json) =>
      _$AskFeedbackResponseFromJson(json);
}

@JsonSerializable()
class ChunkSource {
  const ChunkSource({
    required this.documentId,
    required this.filename,
    required this.locator,
    required this.snippet,
  });

  final String documentId;
  final String filename;
  final String locator;
  final String snippet;

  factory ChunkSource.fromJson(Map<String, dynamic> json) =>
      _$ChunkSourceFromJson(json);
}
