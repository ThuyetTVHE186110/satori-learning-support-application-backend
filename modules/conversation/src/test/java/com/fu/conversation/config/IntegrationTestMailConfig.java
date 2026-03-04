package com.fu.conversation.config;

// This file is intentionally empty — IntegrationTestMailConfig is no longer needed.
// JavaMailSender is handled by @MockBean EmailService in each test class,
// which prevents EmailServiceImpl from being instantiated (same as user module pattern).

