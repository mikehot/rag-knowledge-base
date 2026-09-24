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

  @GET('/api/documents/{id}/acl')
  Future<ApiEnvelope<List<DocumentAclItem>>> listDocumentAcl(
    @Path('id') String id,
  );

  @GET('/api/documents/{id}/acl/principals')
  Future<ApiEnvelope<List<PrincipalOption>>> listDocumentAclPrincipals(
    @Path('id') String id,
    @Query('type') String principalType,
  );

  @POST('/api/documents/{id}/acl')
  Future<ApiEnvelope<DocumentAclItem>> grantDocumentAcl(
    @Path('id') String id,
    @Body() GrantDocumentAclRequest request,
  );

  @DELETE('/api/documents/{id}/acl/{aclId}')
  Future<ApiEnvelope<DeleteDocumentAclResponse>> revokeDocumentAcl(
    @Path('id') String id,
    @Path('aclId') String aclId,
  );

  @GET('/api/admin/users')
  Future<ApiEnvelope<List<PrincipalOption>>> listUsers();

  @GET('/api/admin/departments')
  Future<ApiEnvelope<List<PrincipalOption>>> listDepartments();

  @GET('/api/admin/roles')
  Future<ApiEnvelope<List<PrincipalOption>>> listRoles();

  @GET('/api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks')
  Future<ApiEnvelope<IndexTaskListResponse>> listIndexTasks(
    @Path('knowledgeBaseId') String knowledgeBaseId,
    @Query('limit') int limit,
  );

  @POST(
    '/api/admin/knowledge-bases/{knowledgeBaseId}/index-tasks/{taskId}/retry',
  )
  Future<ApiEnvelope<IndexTaskItem>> retryIndexTask(
    @Path('knowledgeBaseId') String knowledgeBaseId,
    @Path('taskId') String taskId,
  );

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
