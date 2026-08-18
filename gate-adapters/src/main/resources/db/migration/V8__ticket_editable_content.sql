-- Flyway V8: editable ticket content for the web detail page.
--
-- Title already existed in V1. These fields are intentionally nullable/optional so existing
-- tickets remain valid and can be enriched from the detail page later.
ALTER TABLE ticket ADD COLUMN description TEXT;
ALTER TABLE ticket ADD COLUMN note TEXT;
ALTER TABLE ticket ADD COLUMN labels TEXT;
