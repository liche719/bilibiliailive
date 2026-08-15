ALTER TABLE reply_candidates
    ADD COLUMN sender_name VARCHAR(128);

UPDATE reply_candidates
SET sender_name = sender_id
WHERE sender_name IS NULL;

ALTER TABLE reply_candidates
    ALTER COLUMN sender_name SET NOT NULL;
