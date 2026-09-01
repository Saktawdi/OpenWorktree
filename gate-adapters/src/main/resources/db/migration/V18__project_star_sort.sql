-- V18: project star/pin + manual drag ordering for the console home board.
ALTER TABLE project ADD COLUMN starred INTEGER NOT NULL DEFAULT 0;
ALTER TABLE project ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
