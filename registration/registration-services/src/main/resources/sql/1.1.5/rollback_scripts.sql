ALTER TABLE reg.ca_cert_store DROP CONSTRAINT PK_CACS_ID;
DROP TABLE reg.ca_cert_store;
ALTER TABLE "REG"."SYNC_JOB_DEF" DROP COLUMN "JOB_TYPE";