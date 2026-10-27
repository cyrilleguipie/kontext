ALTER TABLE document DROP CONSTRAINT IF EXISTS document_source_check;
ALTER TABLE document ADD CONSTRAINT document_source_check
  CHECK (source IN ('jira', 'confluence', 'manual', 'files', 'notion', 'gitbook', 'github'));
