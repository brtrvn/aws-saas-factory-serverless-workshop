/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.amazon.aws.partners.saasfactory;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.AutoScalingGroup;
import software.amazon.awssdk.services.codedeploy.model.DeploymentGroupInfo;
import software.amazon.awssdk.services.codedeploy.model.GetDeploymentGroupResponse;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class UpdateDeploymentGroup implements RequestHandler<Map<String, Object>, Object> {

	private static final Logger LOGGER = LoggerFactory.getLogger(UpdateDeploymentGroup.class);
	private final CodeDeployClient codeDeploy;

 	public UpdateDeploymentGroup() {
		this.codeDeploy = Utils.sdkClient(CodeDeployClient.builder(), CodeDeployClient.SERVICE_NAME);
 	}

	@Override
	public Object handleRequest(Map<String, Object> event, Context context) {
		Utils.logRequestEvent(event);
		final String requestType = (String) event.get("RequestType");
		Map<String, Object> resourceProperties = (Map<String, Object>) event.get("ResourceProperties");
		final String codeDeployApplication = (String) resourceProperties.get("ApplicationName");
		final String deploymentGroup = (String) resourceProperties.get("DeploymentGroup");
		final String autoScalingGroup = (String) resourceProperties.get("AutoScalingGroup");

		ExecutorService service = Executors.newSingleThreadExecutor();
		Map<String, Object> responseData = new HashMap<>();
		try {
			if (requestType == null) {
				throw new RuntimeException();
			}
			Runnable r = () -> {
				// We have to get the current state of the deployment group because
				// CodeDeploy::UpdateDeploymentGroup is destructive not additive
				GetDeploymentGroupResponse existingDeploymentGroup = codeDeploy.getDeploymentGroup(request -> request
						.applicationName(codeDeployApplication)
						.deploymentGroupName(deploymentGroup)
				);
				DeploymentGroupInfo deploymentGroupInfo = existingDeploymentGroup.deploymentGroupInfo();
				List<AutoScalingGroup> existingAutoScalingGroups = deploymentGroupInfo.autoScalingGroups();

				if ("Create".equalsIgnoreCase(requestType) || "Update".equalsIgnoreCase(requestType)) {
					LOGGER.info("CREATE or UPDATE");

					// Add the requested auto scaling group to the deployment group's
					List<String> autoScalingGroups = new ArrayList<>(Arrays.asList(autoScalingGroup));
					existingAutoScalingGroups
							.stream()
							.map(asg -> asg.name())
							.forEachOrdered(autoScalingGroups::add);

					codeDeploy.updateDeploymentGroup(request -> request
							.applicationName(codeDeployApplication)
							.currentDeploymentGroupName(deploymentGroup)
							.autoScalingGroups(autoScalingGroups)
					);

					CloudFormationResponse.send(event, context, "SUCCESS", responseData);
				} else if ("Delete".equalsIgnoreCase(requestType)) {
					LOGGER.info("DELETE");

					// Filter out the auto scaling group we're deleting and call update
					List<String> autoScalingGroups = existingAutoScalingGroups
							.stream()
							.map(asg -> asg.name())
							.filter(asg -> !autoScalingGroup.equals(asg))
							.collect(Collectors.toList());

					codeDeploy.updateDeploymentGroup(request -> request
							.applicationName(codeDeployApplication)
							.currentDeploymentGroupName(deploymentGroup)
							.autoScalingGroups(autoScalingGroups)
					);

					CloudFormationResponse.send(event, context, "SUCCESS", responseData);
				} else {
					LOGGER.error("FAILED unknown requestType {}", requestType);
					responseData.put("Reason", "Unknown RequestType " + requestType);
					CloudFormationResponse.send(event, context, "FAILED", responseData);
				}
			};
			Future<?> f = service.submit(r);
			f.get(context.getRemainingTimeInMillis() - 1000, TimeUnit.MILLISECONDS);
		} catch (final TimeoutException | InterruptedException | ExecutionException e) {
			// Timed out
			LOGGER.error("FAILED unexpected error or request timed out", e);
			LOGGER.error(Utils.getFullStackTrace(e));
			responseData.put("Reason", e.getMessage());
			CloudFormationResponse.send(event, context, "FAILED", responseData);
		} finally {
			service.shutdown();
		}
		return null;
	}

}