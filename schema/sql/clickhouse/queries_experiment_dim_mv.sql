-- Fast dimensioned experiment views backed by daily MVs

CREATE OR REPLACE VIEW v_exp_exposures_by_day_dim_fast AS
SELECT game_id, environment, event_date, exp, variant, platform, app_version, country, exposures, users
FROM exp_daily_exposures_dim;

CREATE OR REPLACE VIEW v_exp_conversion_24h_dim_fast AS
SELECT game_id, environment, exposure_date, exp, variant, platform, app_version, country,
       exposed_users, converted_users, cr_24h
FROM exp_daily_conv_24h_dim;

CREATE OR REPLACE VIEW v_exp_conversion_7d_dim_fast AS
SELECT game_id, environment, exposure_date, exp, variant, platform, app_version, country,
       exposed_users, converted_users, cr_7d
FROM exp_daily_conv_7d_dim;

