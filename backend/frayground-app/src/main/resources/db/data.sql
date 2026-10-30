-- First-time empty database seed only. Do not replay against an existing database.
USE frayground;

SET @agent_a='00000000-0000-4000-8000-000000000001';
SET @agent_b='00000000-0000-4000-8000-000000000002';
SET @prompt_a='00000000-0000-4000-8000-000000000011';
SET @prompt_b='00000000-0000-4000-8000-000000000012';
SET @config_a='00000000-0000-4000-8000-000000000021';
SET @config_b='00000000-0000-4000-8000-000000000022';
SET @room='00000000-0000-4000-8000-000000000031';
SET @prompt='根据当前观察和合法动作选择行动；只返回要求的 JSON。';
START TRANSACTION;
INSERT INTO agents(id,name,created_at) VALUES
  (@agent_a,'Agent A',UTC_TIMESTAMP(6)),(@agent_b,'Agent B',UTC_TIMESTAMP(6));
INSERT INTO prompt_drafts VALUES
  (@agent_a,1,@prompt,UTC_TIMESTAMP(6)),(@agent_b,1,@prompt,UTC_TIMESTAMP(6));
INSERT INTO prompt_versions VALUES
  (@prompt_a,@agent_a,1,1,@prompt,UNHEX(SHA2(@prompt,256)),UTC_TIMESTAMP(6)),
  (@prompt_b,@agent_b,1,1,@prompt,UNHEX(SHA2(@prompt,256)),UTC_TIMESTAMP(6));
SET @configuration=JSON_OBJECT(
  'model',JSON_OBJECT('provider','deepseek','modelId','deepseek-flash',
    'thinking',CAST('false' AS JSON),'temperature',0,'maxOutputTokens',256,
    'decisionTimeoutMs',30000,'outputStrategy','JSON_MODE',
    'allowBeta',CAST('false' AS JSON)),
  'credentialRef','env:DEEPSEEK_API_KEY',
  'memoryPolicy',JSON_OBJECT('mode','STATELESS'));
INSERT INTO agent_configs VALUES
  (@config_a,@agent_a,1,'MOCK',@configuration,UTC_TIMESTAMP(6)),
  (@config_b,@agent_b,1,'MOCK',@configuration,UTC_TIMESTAMP(6));
UPDATE agents SET published_prompt_id=@prompt_a,current_config_id=@config_a WHERE id=@agent_a;
UPDATE agents SET published_prompt_id=@prompt_b,current_config_id=@config_b WHERE id=@agent_b;
INSERT INTO rooms(id,singleton_key,revision,event_seq,execution_mode,execution_status,health,created_at)
  VALUES(@room,1,0,0,'STEP','PAUSED','OK',UTC_TIMESTAMP(6));
COMMIT;
