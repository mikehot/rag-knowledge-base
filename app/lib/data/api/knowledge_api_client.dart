import 'dart:io';

import 'package:dio/dio.dart';
import 'package:retrofit/retrofit.dart';

import '../models/api_envelope.dart';
import '../models/ask_models.dart';
import '../models/auth_models.dart';
import '../models/document_models.dart';

part 'knowledge_api_client.g.dart';

@RestApi()
abstract class KnowledgeApiClient {
  factory KnowledgeApiClient(Dio dio, {String baseUrl}) = _KnowledgeApiClient;

  @POST('/api/auth/login')
  Future<ApiEnvelope<LoginResponse>> login(@Body() LoginRequest request);

  @MultiPart()
  @POST('/api/documents/upload')
  Future<ApiEnvelope<DocumentUploadResponse>> uploadDocument(
    @Part(name: 'file') File file,
  );

  @GET('/api/documents')
  Future<ApiEnvelope<DocumentListResponse>> listDocuments();

  @GET('/api/documents/{id}')
  Future<ApiEnvelope<DocumentItem>> getDocument(@Path('id') String id);

  @DELETE('/api/documents/{id}')
  Future<ApiEnvelope<DeleteDocumentResponse>> deleteDocument(
    @Path('id') String id,
  );

  @POST('/api/documents/{id}/disable')
  Future<ApiEnvelope<DocumentLifecycleResponse>> disableDocument(
    @Path('id') String id,
  );

  @POST('/api/documents/{id}/reindex')
  Future<ApiEnvelope<DocumentLifecycleResponse>> reindexDocument(
    @Path('id') String id,
  );

  @POST('/api/ask')
  Future<ApiEnvelope<AskAnswer>> ask(@Body() AskRequest request);

  @PUT('/api/ask/{requestId}/feedback')
  Future<ApiEnvelope<AskFeedbackResponse>> submitFeedback(
    @Path('requestId') String requestId,
    @Body() AskFeedbackRequest request,
  );
}
