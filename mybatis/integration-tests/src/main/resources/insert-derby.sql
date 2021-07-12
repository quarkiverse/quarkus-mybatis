CREATE TABLE USERS (
    id integer not null primary key,
    name varchar(80) not null,
    external_id varchar(50) not null
);

DELETE FROM users;
insert into users (id, name, external_id) values (1, 'Test User1', 'ccb16b65-8924-4c3f-8c55-681d85a16e79');
insert into users (id, name, external_id) values (2, 'Test User2', 'ae43f233-0b69-4c4e-bfa9-656c475150ad');
insert into users (id, name, external_id) values (3, 'Test User3', '5640e179-466c-427e-9747-4cfac09a2f9a');
insert into users (id, name, external_id) values (4, 'Test User4', '5640e179-466c-427e-9747-4cfacsfdsdfd');
