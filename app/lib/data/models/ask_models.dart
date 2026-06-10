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
  });

  final String answer;
  final bool found;
  final List<ChunkSource> sources;
  final int tokenUsage;

  factory AskAnswer.fromJson(Map<String, dynamic> json) =>
      _$AskAnswerFromJson(json);
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
