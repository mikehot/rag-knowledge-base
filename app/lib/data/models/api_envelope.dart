import 'package:json_annotation/json_annotation.dart';

part 'api_envelope.g.dart';

@JsonSerializable(genericArgumentFactories: true)
class ApiEnvelope<T> {
  const ApiEnvelope({
    required this.code,
    required this.message,
    required this.data,
  });

  final int code;
  final String message;
  final T? data;

  factory ApiEnvelope.fromJson(
    Map<String, dynamic> json,
    T Function(Object? json) fromJsonT,
  ) => _$ApiEnvelopeFromJson(json, fromJsonT);

  Map<String, dynamic> toJson(Object? Function(T value) toJsonT) =>
      _$ApiEnvelopeToJson(this, toJsonT);
}
