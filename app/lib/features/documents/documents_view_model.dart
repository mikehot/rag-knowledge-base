import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/network/api_exception.dart';
import '../../core/network/providers.dart';
import '../../data/models/document_models.dart';
import '../../data/repositories/knowledge_repository.dart';

final documentsViewModelProvider =
    StateNotifierProvider<DocumentsViewModel, DocumentsState>((ref) {
      return DocumentsViewModel(ref.watch(knowledgeRepositoryProvider));
    });

class DocumentsState {
  const DocumentsState({
    this.items = const [],
    this.loading = false,
    this.uploading = false,
    this.operatingDocumentId,
    this.error,
  });

  final List<DocumentItem> items;
  final bool loading;
  final bool uploading;
  final String? operatingDocumentId;
  final String? error;

  DocumentsState copyWith({
    List<DocumentItem>? items,
    bool? loading,
    bool? uploading,
    String? operatingDocumentId,
    bool clearOperatingDocumentId = false,
    String? error,
    bool clearError = false,
  }) {
    return DocumentsState(
      items: items ?? this.items,
      loading: loading ?? this.loading,
      uploading: uploading ?? this.uploading,
      operatingDocumentId: clearOperatingDocumentId
          ? null
          : operatingDocumentId ?? this.operatingDocumentId,
      error: clearError ? null : error ?? this.error,
    );
  }
}

class DocumentsViewModel extends StateNotifier<DocumentsState> {
  DocumentsViewModel(this._repository) : super(const DocumentsState());

  final KnowledgeRepository _repository;

  Future<void> load() async {
    state = state.copyWith(loading: true, clearError: true);
    try {
      final data = await _repository.listDocuments();
      state = state.copyWith(items: data.items, loading: false);
    } catch (error) {
      state = state.copyWith(loading: false, error: _message(error));
    }
  }

  Future<void> pickAndUpload() async {
    final result = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['pdf', 'txt', 'md', 'docx'],
    );
    final file = result?.files.single;
    if (file == null || file.path == null) {
      return;
    }
    state = state.copyWith(uploading: true, clearError: true);
    try {
      final upload = await _repository.uploadDocument(File(file.path!));
      await load();
      await _pollUntilDone(upload.documentId);
      state = state.copyWith(uploading: false);
    } catch (error) {
      state = state.copyWith(uploading: false, error: _message(error));
    }
  }

  Future<void> delete(String documentId) async {
    state = state.copyWith(clearError: true);
    try {
      await _repository.deleteDocument(documentId);
      await load();
    } catch (error) {
      state = state.copyWith(error: _message(error));
    }
  }

  Future<void> disable(String documentId) async {
    await _runLifecycle(
      documentId,
      () => _repository.disableDocument(documentId),
    );
  }

  Future<void> reindex(String documentId) async {
    await _runLifecycle(
      documentId,
      () => _repository.reindexDocument(documentId),
    );
  }

  Future<void> _runLifecycle(
    String documentId,
    Future<DocumentLifecycleResponse> Function() action,
  ) async {
    if (state.operatingDocumentId != null) {
      return;
    }
    state = state.copyWith(operatingDocumentId: documentId, clearError: true);
    try {
      await action();
      await load();
    } catch (error) {
      state = state.copyWith(error: _message(error));
    } finally {
      state = state.copyWith(clearOperatingDocumentId: true);
    }
  }

  Future<void> _pollUntilDone(String documentId) async {
    for (var i = 0; i < 40; i++) {
      await Future<void>.delayed(const Duration(seconds: 2));
      final document = await _repository.getDocument(documentId);
      await load();
      if (document.statusEnum != DocumentStatus.processing) {
        return;
      }
    }
    state = state.copyWith(error: '文档仍在入库中，可稍后刷新查看');
  }

  String _message(Object error) {
    return error is ApiException ? error.message : '操作失败，请重试';
  }
}
