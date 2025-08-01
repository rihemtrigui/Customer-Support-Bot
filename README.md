## Project Overview
This project is an AWS-based application developed using the AWS Cloud Development Kit (CDK) with Java. It features a conversational AI system with Lex, voice integration via Connect, and a Card Verification API. The architecture includes Lambda functions for handling FAQs, orchestrating workflows, and processing orders, supported by a RAG system with Bedrock LLM, FAISS Vector Database, and DynamoDB for data management. S3 Buckets store indexes and client orders, while Secrets Manager secures sensitive data.

<img width="607" height="394" alt="Cloudairy project archi" src="https://github.com/user-attachments/assets/0690e5f6-9217-4375-a9b4-b57508a0ec3c" />

## Getting Started
This is a blank project for CDK development with Java. The `cdk.json` file tells the CDK Toolkit how to execute your app. It is a Maven-based project, so you can open this project with any Maven-compatible Java IDE to build and run tests.


### Useful Commands
- `mvn package` - Compile and run tests
- `cdk ls` - List all stacks in the app
- `cdk synth` - Emit the synthesized CloudFormation template
- `cdk deploy` - Deploy this stack to your default AWS account/region
- `cdk diff` - Compare deployed stack with current state
- `cdk docs` - Open CDK documentation

## Enjoy!
Feel free to explore and enhance the architecture as needed!
