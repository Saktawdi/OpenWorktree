-- V6: project meta — console-managed priority / size / tags for the home board.
ALTER TABLE project ADD COLUMN priority TEXT;
ALTER TABLE project ADD COLUMN size TEXT;
ALTER TABLE project ADD COLUMN tags TEXT;
