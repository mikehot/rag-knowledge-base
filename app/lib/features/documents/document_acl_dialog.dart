import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/providers.dart';
import '../../data/models/document_models.dart';
import '../../data/repositories/knowledge_repository.dart';

Future<void> showDocumentAclDialog(
  BuildContext context, {
  required String documentId,
  required String filename,
}) async {
  await showDialog<void>(
    context: context,
    builder: (context) =>
        _DocumentAclDialog(documentId: documentId, filename: filename),
  );
}

class _DocumentAclDialog extends ConsumerStatefulWidget {
  const _DocumentAclDialog({required this.documentId, required this.filename});

  final String documentId;
  final String filename;

  @override
  ConsumerState<_DocumentAclDialog> createState() => _DocumentAclDialogState();
}

class _DocumentAclDialogState extends ConsumerState<_DocumentAclDialog> {
  String _principalType = 'USER';
  String _permission = 'READ';
  String? _principalId;
  List<DocumentAclItem> _acl = const [];
  List<PrincipalOption> _principals = const [];
  bool _loading = true;
  bool _saving = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final repository = _repository;
      final acl = await repository.listDocumentAcl(widget.documentId);
      if (!mounted) return;
      setState(() {
        _acl = acl;
      });
      try {
        final principals = await repository.listAclPrincipals(_principalType);
        if (!mounted) return;
        setState(() {
          _principals = principals
              .where(
                (principal) =>
                    principal.status == null || principal.status == 'ACTIVE',
              )
              .toList();
          _principalId = _principals.any((item) => item.id == _principalId)
              ? _principalId
              : null;
          _loading = false;
        });
      } catch (error) {
        if (!mounted) return;
        setState(() {
          _loading = false;
          _error = '权限列表读取失败：$error';
        });
      }
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = error.toString();
      });
    }
  }

  KnowledgeRepository get _repository => ref.read(knowledgeRepositoryProvider);

  Future<void> _grant() async {
    final principalId = _principalId;
    if (principalId == null || _saving) return;
    setState(() => _saving = true);
    try {
      await _repository.grantDocumentAcl(
        widget.documentId,
        GrantDocumentAclRequest(
          principalType: _principalType,
          principalId: principalId,
          permission: _permission,
        ),
      );
      await _load();
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _revoke(DocumentAclItem item) async {
    setState(() => _saving = true);
    try {
      await _repository.revokeDocumentAcl(widget.documentId, item.id);
      await _load();
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _confirmRevoke(DocumentAclItem item) async {
    final approved = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('撤销文档权限？'),
        content: Text(
          '将撤销 ${item.principalType} 对此文档的 ${item.permission} 权限。'
          '变更保存后，该主体不应再检索或引用此文档。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('撤销权限'),
          ),
        ],
      ),
    );
    if (approved == true && mounted) await _revoke(item);
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(
        '文档权限 · ${widget.filename}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      content: SizedBox(
        width: 520,
        child: _loading
            ? const SizedBox(
                height: 100,
                child: Center(child: CircularProgressIndicator()),
              )
            : SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (_error != null) ...[
                      Text(_error!, style: const TextStyle(color: Colors.red)),
                      TextButton(onPressed: _load, child: const Text('重试')),
                    ],
                    DropdownButtonFormField<String>(
                      key: ValueKey('type-$_principalType'),
                      initialValue: _principalType,
                      decoration: const InputDecoration(labelText: '主体类型'),
                      items: const [
                        DropdownMenuItem(value: 'USER', child: Text('用户')),
                        DropdownMenuItem(
                          value: 'DEPARTMENT',
                          child: Text('部门'),
                        ),
                        DropdownMenuItem(value: 'ROLE', child: Text('角色')),
                      ],
                      onChanged: _saving
                          ? null
                          : (value) {
                              if (value == null) return;
                              setState(() {
                                _principalType = value;
                                _principalId = null;
                              });
                              _load();
                            },
                    ),
                    DropdownButtonFormField<String>(
                      key: ValueKey(
                        'principal-$_principalId-${_principals.length}',
                      ),
                      initialValue: _principalId,
                      decoration: const InputDecoration(labelText: '授权对象'),
                      items: _principals
                          .map(
                            (principal) => DropdownMenuItem(
                              value: principal.id,
                              child: Text(
                                principal.label,
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          )
                          .toList(),
                      onChanged: _saving
                          ? null
                          : (value) => setState(() => _principalId = value),
                    ),
                    DropdownButtonFormField<String>(
                      key: ValueKey('permission-$_permission'),
                      initialValue: _permission,
                      decoration: const InputDecoration(labelText: '权限'),
                      items: const [
                        DropdownMenuItem(value: 'READ', child: Text('只读')),
                        DropdownMenuItem(value: 'MANAGE', child: Text('管理')),
                      ],
                      onChanged: _saving
                          ? null
                          : (value) {
                              if (value != null) {
                                setState(() => _permission = value);
                              }
                            },
                    ),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: _principalId == null || _saving
                          ? null
                          : _grant,
                      icon: const Icon(Icons.person_add_alt_1),
                      label: const Text('授权'),
                    ),
                    const Divider(height: 24),
                    if (_acl.isEmpty)
                      const Text('当前没有显式文档授权')
                    else
                      ..._acl.map(
                        (item) => ListTile(
                          dense: true,
                          contentPadding: EdgeInsets.zero,
                          title: Text(
                            '${item.principalType} · ${item.principalId}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(item.permission),
                          trailing: IconButton(
                            tooltip: '撤权',
                            onPressed: _saving
                                ? null
                                : () => _confirmRevoke(item),
                            icon: const Icon(Icons.remove_circle_outline),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}
