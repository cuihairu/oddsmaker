-- Add database_name column to game_environments
-- This column was added to the entity but missing from the original migration

ALTER TABLE game_environments ADD COLUMN IF NOT EXISTS database_name VARCHAR(255);

-- Create index for database_name lookups
CREATE INDEX IF NOT EXISTS idx_game_environments_database_name ON game_environments(database_name);
