-- Older baseline databases may have an app_user.created_at column without
-- the default declared by the current V1 schema. Admin user creation relies
-- on the database default, so repair that compatibility path explicitly.
ALTER TABLE app_user
    ALTER COLUMN created_at SET DEFAULT now();
