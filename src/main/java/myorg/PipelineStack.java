package myorg;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.pipelines.CodeBuildOptions;
import software.amazon.awscdk.pipelines.CodePipeline;
import software.amazon.awscdk.pipelines.CodePipelineSource;
import software.amazon.awscdk.pipelines.ShellStep;
import software.amazon.awscdk.services.codecommit.IRepository;
import software.amazon.awscdk.services.codecommit.Repository;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.constructs.Construct;
import java.util.Arrays;

public class PipelineStack extends Stack {
    public PipelineStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        IRepository repository = Repository.fromRepositoryName(this, "CodeCommitRepo", "Customer-Support-Bot");

        CodePipelineSource source = CodePipelineSource.codeCommit(
                repository,
                "main",
                null
        );

        // Define the synth step using ShellStep with inline commands
        ShellStep synthStep = ShellStep.Builder.create("Synth")
                .input(source)
                .commands(Arrays.asList(
                        "echo Installing Java runtime...",
                        "java -version",
                        "echo Installing Maven dependencies...",
                        "mvn clean install",
                        "echo Running tests for SimpleHandler...",
                        "mvn test",
                        "echo Installing AWS CDK...",
                        "npm install -g aws-cdk",
                        "mvn package",
                        "echo Checking AWS account settings...",
                        "aws sts get-caller-identity",
                        "echo Bootstrapping AWS environment if needed...",
                        "npx cdk synth"
                ))
                .primaryOutputDirectory("cdk.out")  // Specify the output directory
                .build();

        // Define the pipeline
        CodePipeline pipeline = CodePipeline.Builder.create(this, "Pipeline")
                .pipelineName("HPSmartBotPipeline")  // Fixed typo in pipeline name
                .synth(synthStep)
                .codeBuildDefaults(CodeBuildOptions.builder()
                        .rolePolicy(Arrays.asList(
                                // General permissions
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .actions(Arrays.asList(
                                                "cloudformation:*",
                                                "iam:*",
                                                "lambda:*",
                                                "dynamodb:*",
                                                "lex:*",
                                                "ecr:*",
                                                "ses:*",
                                                "codecommit:*"
                                        ))
                                        .resources(Arrays.asList("*"))
                                        .build(),
                                // Explicit S3 permissions
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .actions(Arrays.asList(
                                                "s3:GetObject*",
                                                "s3:GetBucket*",
                                                "s3:List*",
                                                "s3:DeleteObject*",
                                                "s3:PutObject*",
                                                "s3:Abort*",
                                                "s3:CreateBucket"
                                        ))
                                        .resources(Arrays.asList(
                                                "arn:aws:s3:::*"
                                        ))
                                        .build(),
                                PolicyStatement.Builder.create()
                                        .effect(Effect.ALLOW)
                                        .actions(Arrays.asList(
                                                "s3:GetObject*",
                                                "s3:GetBucket*",
                                                "s3:List*",
                                                "s3:DeleteObject*",
                                                "s3:PutObject*",
                                                "s3:Abort*"
                                        ))
                                        .resources(Arrays.asList(
                                                "arn:aws:s3:::cdk-*-assets-*",
                                                "arn:aws:s3:::cdk-*-assets-*/*",
                                                "arn:aws:s3:::cdktoolkit-stagingbucket-*",
                                                "arn:aws:s3:::cdktoolkit-stagingbucket-*/*"
                                        ))
                                        .build()
                        ))
                        .build())
                .build();

        pipeline.addStage(new CustomerSupportBotStage(this, "Deploy"));
    }
}