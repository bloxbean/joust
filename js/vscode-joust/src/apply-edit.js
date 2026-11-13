// ADR-0021 D4a (r5, review C9): the extension's own workspace/applyEdit handler, installed as the client's
// middleware.workspace.handleApplyEdit, which REPLACES the default handler (it never calls `next`). The default one checks
// versions only of documents that are open, and VS Code would then apply to a closed target without a version.
//
//   1. convert the edit (asynchronous);
//   2. then, synchronously, with no await until the call: every target must be a versioned TextDocumentEdit whose
//      document is open in workspace.textDocuments at exactly that version, else the WHOLE edit is refused;
//   3. call workspace.applyEdit(converted), which captures the just-checked versions and has VS Code check them again
//      before applying anything.
// A target that closed while the edit was pending is refused, never reopened and stamped with a new version.

/** `vscode`: the API (or a fake, in tests); `client`: the LanguageClient (its protocol2CodeConverter). */
function createHandleApplyEdit(vscode, getClient) {
  return async function handleApplyEdit(params) {
    const edit = params && params.edit;
    const changes = edit && edit.documentChanges;
    // Only versioned text edits: no `changes` map (unversioned), no file creates, renames or deletes.
    if (!edit || edit.changes || !Array.isArray(changes) || changes.length === 0) {
      return { applied: false, failureReason: 'JOUST applies only versioned edits of open documents' };
    }
    for (const c of changes) {
      if (!c || !c.textDocument || typeof c.textDocument.version !== 'number' || !Array.isArray(c.edits)) {
        return { applied: false, failureReason: 'JOUST applies only versioned edits of open documents' };
      }
    }
    // S9 on #83: the server sends the text every edit replaces; an edit without it is not the server's checked edit.
    const expected = params.joustExpected;
    if (!Array.isArray(expected) || expected.length !== changes.reduce((n, c) => n + c.edits.length, 0)) {
      return { applied: false, failureReason: 'JOUST applies only edits that carry the text they replace' };
    }
    const client = getClient();
    // Review C2 on #83: a version identifies text only within one open lifetime. The target documents are captured now,
    // before the conversion's await, and the very same documents must still be open afterwards: a target closed and
    // reopened meanwhile (a new document at a reused version) is refused.
    const before = new Map();
    for (const c of changes) {
      const uri = client.protocol2CodeConverter.asUri(c.textDocument.uri).toString();
      const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri);
      if (!doc || doc.isClosed) return { applied: false, failureReason: `${uri} is not open: the edit is refused` };
      before.set(c, doc);
    }
    let converted;
    try {
      converted = await client.protocol2CodeConverter.asWorkspaceEdit(edit);
    } catch (e) {
      return { applied: false, failureReason: `the edit could not be converted: ${e && e.message}` };
    }
    // From here to applyEdit: no await (the versions checked are the versions applied).
    for (const c of changes) {
      const uri = client.protocol2CodeConverter.asUri(c.textDocument.uri).toString();
      const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri);
      if (!doc || doc.isClosed) return { applied: false, failureReason: `${uri} is not open: the edit is refused` };
      if (doc !== before.get(c)) return { applied: false, failureReason: `${uri} was closed and reopened: the edit is refused` };
      if (doc.version !== c.textDocument.version) {
        return { applied: false, failureReason: `${uri} changed since the edit was checked: the edit is refused` };
      }
    }
    // Still synchronous: the text each edit replaces is the text the server checked (a reopen at a reused version that
    // happened before this handler started has other text there).
    for (const x of expected) {
      const uri = client.protocol2CodeConverter.asUri(x.uri).toString();
      const doc = vscode.workspace.textDocuments.find((d) => d.uri.toString() === uri);
      if (!doc || typeof x.text !== 'string' || doc.getText(client.protocol2CodeConverter.asRange(x.range)) !== x.text) {
        return { applied: false, failureReason: `${uri} no longer has the text the edit was checked on: the edit is refused` };
      }
    }
    return vscode.workspace.applyEdit(converted).then((applied) => ({ applied: applied === true }));
  };
}

module.exports = { createHandleApplyEdit };
