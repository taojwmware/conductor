/*
 * Copyright 2022 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.core.execution;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.netflix.conductor.annotations.Trace;
import com.netflix.conductor.common.metadata.tasks.*;
import com.netflix.conductor.common.metadata.workflow.RerunWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.SkipTaskRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.utils.RetryUtil;
import com.netflix.conductor.common.utils.TaskUtils;
import com.netflix.conductor.core.WorkflowContext;
import com.netflix.conductor.core.config.ConductorProperties;
import com.netflix.conductor.core.dal.ExecutionDAOFacade;
import com.netflix.conductor.core.exception.ApplicationException;
import com.netflix.conductor.core.exception.ApplicationException.Code;
import com.netflix.conductor.core.exception.TerminateWorkflowException;
import com.netflix.conductor.core.execution.tasks.SystemTaskRegistry;
import com.netflix.conductor.core.execution.tasks.Terminate;
import com.netflix.conductor.core.execution.tasks.WorkflowSystemTask;
import com.netflix.conductor.core.listener.WorkflowStatusListener;
import com.netflix.conductor.core.metadata.MetadataMapperService;
import com.netflix.conductor.core.utils.IDGenerator;
import com.netflix.conductor.core.utils.ParametersUtils;
import com.netflix.conductor.core.utils.QueueUtils;
import com.netflix.conductor.core.utils.Utils;
import com.netflix.conductor.dao.MetadataDAO;
import com.netflix.conductor.dao.QueueDAO;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.model.TaskModel;
import com.netflix.conductor.model.WorkflowModel;
import com.netflix.conductor.service.ExecutionLockService;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import static com.netflix.conductor.core.exception.ApplicationException.Code.*;
import static com.netflix.conductor.model.TaskModel.Status.*;

/** Workflow services provider interface */
@Trace
@Component
public class WorkflowExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowExecutor.class);
    private static final int PARENT_WF_PRIORITY = 10;

    private final MetadataDAO metadataDAO;
    private final QueueDAO queueDAO;
    private final DeciderService deciderService;
    private final ConductorProperties properties;
    private final MetadataMapperService metadataMapperService;
    private final ExecutionDAOFacade executionDAOFacade;
    private final ParametersUtils parametersUtils;
    private final WorkflowStatusListener workflowStatusListener;
    private final SystemTaskRegistry systemTaskRegistry;

    private long activeWorkerLastPollMs;
    private static final String CLASS_NAME = WorkflowExecutor.class.getSimpleName();
    private final ExecutionLockService executionLockService;

    private static final Predicate<TaskModel> UNSUCCESSFUL_TERMINAL_TASK =
            task -> !task.getStatus().isSuccessful() && task.getStatus().isTerminal();

    private static final Predicate<TaskModel> UNSUCCESSFUL_JOIN_TASK =
            UNSUCCESSFUL_TERMINAL_TASK.and(t -> TaskType.TASK_TYPE_JOIN.equals(t.getTaskType()));

    private static final Predicate<TaskModel> NON_TERMINAL_TASK =
            task -> !task.getStatus().isTerminal();

    private final Predicate<PollData> validateLastPolledTime =
            pollData ->
                    pollData.getLastPollTime()
                            > System.currentTimeMillis() - activeWorkerLastPollMs;

    public WorkflowExecutor(
            DeciderService deciderService,
            MetadataDAO metadataDAO,
            QueueDAO queueDAO,
            MetadataMapperService metadataMapperService,
            WorkflowStatusListener workflowStatusListener,
            ExecutionDAOFacade executionDAOFacade,
            ConductorProperties properties,
            ExecutionLockService executionLockService,
            SystemTaskRegistry systemTaskRegistry,
            ParametersUtils parametersUtils) {
        this.deciderService = deciderService;
        this.metadataDAO = metadataDAO;
        this.queueDAO = queueDAO;
        this.properties = properties;
        this.metadataMapperService = metadataMapperService;
        this.executionDAOFacade = executionDAOFacade;
        this.activeWorkerLastPollMs = properties.getActiveWorkerLastPollTimeout().toMillis();
        this.workflowStatusListener = workflowStatusListener;
        this.executionLockService = executionLockService;
        this.parametersUtils = parametersUtils;
        this.systemTaskRegistry = systemTaskRegistry;
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Map<String, Object> input,
            String externalInputPayloadStoragePath) {
        return startWorkflow(
                name, version, correlationId, input, externalInputPayloadStoragePath, null);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Integer priority,
            Map<String, Object> input,
            String externalInputPayloadStoragePath) {
        return startWorkflow(
                name,
                version,
                correlationId,
                priority,
                input,
                externalInputPayloadStoragePath,
                null);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Map<String, Object> input,
            String externalInputPayloadStoragePath,
            String event) {
        return startWorkflow(
                name,
                version,
                input,
                externalInputPayloadStoragePath,
                correlationId,
                null,
                null,
                event);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Integer priority,
            Map<String, Object> input,
            String externalInputPayloadStoragePath,
            String event) {
        return startWorkflow(
                name,
                version,
                input,
                externalInputPayloadStoragePath,
                correlationId,
                priority,
                null,
                null,
                event,
                null);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Map<String, Object> input,
            String externalInputPayloadStoragePath,
            String event,
            Map<String, String> taskToDomain) {
        return startWorkflow(
                name,
                version,
                correlationId,
                0,
                input,
                externalInputPayloadStoragePath,
                event,
                taskToDomain);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            String correlationId,
            Integer priority,
            Map<String, Object> input,
            String externalInputPayloadStoragePath,
            String event,
            Map<String, String> taskToDomain) {
        return startWorkflow(
                name,
                version,
                input,
                externalInputPayloadStoragePath,
                correlationId,
                priority,
                null,
                null,
                event,
                taskToDomain);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            Map<String, Object> input,
            String externalInputPayloadStoragePath,
            String correlationId,
            String parentWorkflowId,
            String parentWorkflowTaskId,
            String event) {
        return startWorkflow(
                name,
                version,
                input,
                externalInputPayloadStoragePath,
                correlationId,
                parentWorkflowId,
                parentWorkflowTaskId,
                event,
                null);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            WorkflowDef workflowDefinition,
            Map<String, Object> workflowInput,
            String externalInputPayloadStoragePath,
            String correlationId,
            String event,
            Map<String, String> taskToDomain) {
        return startWorkflow(
                workflowDefinition,
                workflowInput,
                externalInputPayloadStoragePath,
                correlationId,
                0,
                event,
                taskToDomain);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            WorkflowDef workflowDefinition,
            Map<String, Object> workflowInput,
            String externalInputPayloadStoragePath,
            String correlationId,
            Integer priority,
            String event,
            Map<String, String> taskToDomain) {
        return startWorkflow(
                workflowDefinition,
                workflowInput,
                externalInputPayloadStoragePath,
                correlationId,
                priority,
                null,
                null,
                event,
                taskToDomain);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            Map<String, Object> workflowInput,
            String externalInputPayloadStoragePath,
            String correlationId,
            String parentWorkflowId,
            String parentWorkflowTaskId,
            String event,
            Map<String, String> taskToDomain) {
        return startWorkflow(
                name,
                version,
                workflowInput,
                externalInputPayloadStoragePath,
                correlationId,
                0,
                parentWorkflowId,
                parentWorkflowTaskId,
                event,
                taskToDomain);
    }

    /** @throws ApplicationException */
    public String startWorkflow(
            String name,
            Integer version,
            Map<String, Object> workflowInput,
            String externalInputPayloadStoragePath,
            String correlationId,
            Integer priority,
            String parentWorkflowId,
            String parentWorkflowTaskId,
            String event,
            Map<String, String> taskToDomain) {
        WorkflowDef workflowDefinition =
                metadataMapperService.lookupForWorkflowDefinition(name, version);

        return startWorkflow(
                workflowDefinition,
                workflowInput,
                externalInputPayloadStoragePath,
                correlationId,
                priority,
                parentWorkflowId,
                parentWorkflowTaskId,
                event,
                taskToDomain);
    }

    /** @throws ApplicationException if validation fails */
    public String startWorkflow(
            WorkflowDef workflowDefinition,
            Map<String, Object> workflowInput,
            String externalInputPayloadStoragePath,
            String correlationId,
            Integer priority,
            String parentWorkflowId,
            String parentWorkflowTaskId,
            String event,
            Map<String, String> taskToDomain) {

        workflowDefinition = metadataMapperService.populateTaskDefinitions(workflowDefinition);

        // perform validations
        validateWorkflow(workflowDefinition, workflowInput, externalInputPayloadStoragePath);

        // A random UUID is assigned to the work flow instance
        String workflowId = IDGenerator.generate();

        // Persist the Workflow
        WorkflowModel workflow = new WorkflowModel();
        workflow.setWorkflowId(workflowId);
        workflow.setCorrelationId(correlationId);
        workflow.setPriority(priority == null ? 0 : priority);
        workflow.setWorkflowDefinition(workflowDefinition);
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setParentWorkflowId(parentWorkflowId);
        workflow.setParentWorkflowTaskId(parentWorkflowTaskId);
        workflow.setOwnerApp(WorkflowContext.get().getClientApp());
        workflow.setCreateTime(System.currentTimeMillis());
        workflow.setUpdatedBy(null);
        workflow.setUpdatedTime(null);
        workflow.setEvent(event);
        workflow.setTaskToDomain(taskToDomain);
        workflow.setVariables(workflowDefinition.getVariables());

        if (workflowInput != null && !workflowInput.isEmpty()) {
            Map<String, Object> parsedInput =
                    parametersUtils.getWorkflowInput(workflowDefinition, workflowInput);
            workflow.setInput(parsedInput);
        } else {
            workflow.setExternalInputPayloadStoragePath(externalInputPayloadStoragePath);
        }

        try {
            createWorkflow(workflow);
            // then decide to see if anything needs to be done as part of the workflow
            decide(workflowId);
            Monitors.recordWorkflowStartSuccess(
                    workflow.getWorkflowName(),
                    String.valueOf(workflow.getWorkflowVersion()),
                    workflow.getOwnerApp());
            return workflowId;
        } catch (Exception e) {
            Monitors.recordWorkflowStartError(
                    workflowDefinition.getName(), WorkflowContext.get().getClientApp());
            LOGGER.error("Unable to start workflow: {}", workflowDefinition.getName(), e);

            // It's possible the remove workflow call hits an exception as well, in that case we
            // want to log both errors to help diagnosis.
            try {
                executionDAOFacade.removeWorkflow(workflowId, false);
            } catch (Exception rwe) {
                LOGGER.error("Could not remove the workflowId: " + workflowId, rwe);
            }
            throw e;
        }
    }

    /*
     * Acquire and hold the lock till the workflow creation action is completed (in primary and secondary datastores).
     * This is to ensure that workflow creation action precedes any other action on a given workflow.
     */
    private void createWorkflow(WorkflowModel workflow) {
        if (!executionLockService.acquireLock(workflow.getWorkflowId())) {
            throw new ApplicationException(
                    BACKEND_ERROR, "Error acquiring lock when creating workflow: {}");
        }
        try {
            executionDAOFacade.createWorkflow(workflow);
            LOGGER.debug(
                    "A new instance of workflow: {} created with id: {}",
                    workflow.getWorkflowName(),
                    workflow.getWorkflowId());
        } finally {
            executionLockService.releaseLock(workflow.getWorkflowId());
        }
    }

    /**
     * Performs validations for starting a workflow
     *
     * @throws ApplicationException if the validation fails
     */
    private void validateWorkflow(
            WorkflowDef workflowDef,
            Map<String, Object> workflowInput,
            String externalStoragePath) {
        try {
            // Check if the input to the workflow is not null
            if (workflowInput == null && StringUtils.isBlank(externalStoragePath)) {
                LOGGER.error(
                        "The input for the workflow '{}' cannot be NULL", workflowDef.getName());
                throw new ApplicationException(
                        INVALID_INPUT, "NULL input passed when starting workflow");
            }
        } catch (Exception e) {
            Monitors.recordWorkflowStartError(
                    workflowDef.getName(), WorkflowContext.get().getClientApp());
            throw e;
        }
    }

    /**
     * @param workflowId the id of the workflow for which task callbacks are to be reset
     * @throws ApplicationException if the workflow is in terminal state
     */
    public void resetCallbacksForWorkflow(String workflowId) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (workflow.getStatus().isTerminal()) {
            throw new ApplicationException(
                    CONFLICT, "Workflow is in terminal state. Status =" + workflow.getStatus());
        }

        // Get SIMPLE tasks in SCHEDULED state that have callbackAfterSeconds > 0 and set the
        // callbackAfterSeconds to 0
        workflow.getTasks().stream()
                .filter(
                        task ->
                                !systemTaskRegistry.isSystemTask(task.getTaskType())
                                        && SCHEDULED == task.getStatus()
                                        && task.getCallbackAfterSeconds() > 0)
                .forEach(
                        task -> {
                            if (queueDAO.resetOffsetTime(
                                    QueueUtils.getQueueName(task), task.getTaskId())) {
                                task.setCallbackAfterSeconds(0);
                                executionDAOFacade.updateTask(task);
                            }
                        });
    }

    public String rerun(RerunWorkflowRequest request) {
        Preconditions.checkNotNull(
                request.getReRunFromWorkflowId(), "reRunFromWorkflowId is missing");
        if (!rerunWF(
                request.getReRunFromWorkflowId(),
                request.getReRunFromTaskId(),
                request.getTaskInput(),
                request.getWorkflowInput(),
                request.getCorrelationId())) {
            throw new ApplicationException(
                    INVALID_INPUT, "Task " + request.getReRunFromTaskId() + " not found");
        }
        return request.getReRunFromWorkflowId();
    }

    /**
     * @param workflowId the id of the workflow to be restarted
     * @param useLatestDefinitions if true, use the latest workflow and task definitions upon
     *     restart
     * @throws ApplicationException in the following cases:
     *     <ul>
     *       <li>Workflow is not in a terminal state
     *       <li>Workflow definition is not found
     *       <li>Workflow is deemed non-restartable as per workflow definition
     *     </ul>
     */
    public void restart(String workflowId, boolean useLatestDefinitions) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (!workflow.getStatus().isTerminal()) {
            String errorMsg =
                    String.format(
                            "Workflow: %s is not in terminal state, unable to restart.", workflow);
            LOGGER.error(errorMsg);
            throw new ApplicationException(CONFLICT, errorMsg);
        }

        WorkflowDef workflowDef;
        if (useLatestDefinitions) {
            workflowDef =
                    metadataDAO
                            .getLatestWorkflowDef(workflow.getWorkflowName())
                            .orElseThrow(
                                    () ->
                                            new ApplicationException(
                                                    NOT_FOUND,
                                                    String.format(
                                                            "Unable to find latest definition for %s",
                                                            workflowId)));
            workflow.setWorkflowDefinition(workflowDef);
        } else {
            workflowDef =
                    Optional.ofNullable(workflow.getWorkflowDefinition())
                            .orElseGet(
                                    () ->
                                            metadataDAO
                                                    .getWorkflowDef(
                                                            workflow.getWorkflowName(),
                                                            workflow.getWorkflowVersion())
                                                    .orElseThrow(
                                                            () ->
                                                                    new ApplicationException(
                                                                            NOT_FOUND,
                                                                            String.format(
                                                                                    "Unable to find definition for %s",
                                                                                    workflowId))));
        }

        if (!workflowDef.isRestartable()
                && workflow.getStatus()
                        .equals(
                                WorkflowModel.Status
                                        .COMPLETED)) { // Can only restart non-completed workflows
            // when the configuration is set to false
            throw new ApplicationException(
                    CONFLICT, String.format("Workflow: %s is non-restartable", workflow));
        }

        // Reset the workflow in the primary datastore and remove from indexer; then re-create it
        executionDAOFacade.resetWorkflow(workflowId);

        workflow.getTasks().clear();
        workflow.setReasonForIncompletion(null);
        workflow.setCreateTime(System.currentTimeMillis());
        workflow.setEndTime(0);
        workflow.setLastRetriedTime(0);
        // Change the status to running
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setOutput(null);
        workflow.setExternalOutputPayloadStoragePath(null);

        try {
            executionDAOFacade.createWorkflow(workflow);
        } catch (Exception e) {
            Monitors.recordWorkflowStartError(
                    workflowDef.getName(), WorkflowContext.get().getClientApp());
            LOGGER.error("Unable to restart workflow: {}", workflowDef.getName(), e);
            terminateWorkflow(workflowId, "Error when restarting the workflow");
            throw e;
        }

        decide(workflowId);

        updateAndPushParents(workflow, "restarted");
    }

    /**
     * Gets the last instance of each failed task and reschedule each Gets all cancelled tasks and
     * schedule all of them except JOIN (join should change status to INPROGRESS) Switch workflow
     * back to RUNNING status and call decider.
     *
     * @param workflowId the id of the workflow to be retried
     */
    public void retry(String workflowId, boolean resumeSubworkflowTasks) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (!workflow.getStatus().isTerminal()) {
            throw new ApplicationException(
                    CONFLICT, "Workflow is still running.  status=" + workflow.getStatus());
        }
        if (workflow.getTasks().isEmpty()) {
            throw new ApplicationException(CONFLICT, "Workflow has not started yet");
        }

        if (resumeSubworkflowTasks) {
            Optional<TaskModel> taskToRetry =
                    workflow.getTasks().stream().filter(UNSUCCESSFUL_TERMINAL_TASK).findFirst();
            if (taskToRetry.isPresent()) {
                workflow = findLastFailedSubWorkflowIfAny(taskToRetry.get(), workflow);
                retry(workflow);
                updateAndPushParents(workflow, "retried");
            }
        } else {
            retry(workflow);
            updateAndPushParents(workflow, "retried");
        }
    }

    private void updateAndPushParents(WorkflowModel workflow, String operation) {
        String workflowIdentifier = "";
        while (workflow.hasParent()) {
            // update parent's sub workflow task
            TaskModel subWorkflowTask =
                    executionDAOFacade.getTaskModel(workflow.getParentWorkflowTaskId());
            subWorkflowTask.setSubworkflowChanged(true);
            subWorkflowTask.setStatus(IN_PROGRESS);
            executionDAOFacade.updateTask(subWorkflowTask);

            // add an execution log
            String currentWorkflowIdentifier = workflow.toShortString();
            workflowIdentifier =
                    !workflowIdentifier.equals("")
                            ? String.format(
                                    "%s -> %s", currentWorkflowIdentifier, workflowIdentifier)
                            : currentWorkflowIdentifier;
            TaskExecLog log =
                    new TaskExecLog(
                            String.format("Sub workflow %s %s.", workflowIdentifier, operation));
            log.setTaskId(subWorkflowTask.getTaskId());
            executionDAOFacade.addTaskExecLog(Collections.singletonList(log));
            LOGGER.info("Task {} updated. {}", log.getTaskId(), log.getLog());

            // push the parent workflow to decider queue for asynchronous 'decide'
            String parentWorkflowId = workflow.getParentWorkflowId();
            WorkflowModel parentWorkflow =
                    executionDAOFacade.getWorkflowModel(parentWorkflowId, true);
            parentWorkflow.setStatus(WorkflowModel.Status.RUNNING);
            parentWorkflow.setLastRetriedTime(System.currentTimeMillis());
            executionDAOFacade.updateWorkflow(parentWorkflow);
            pushParentWorkflow(parentWorkflowId);

            workflow = parentWorkflow;
        }
    }

    private void retry(WorkflowModel workflow) {
        // Get all FAILED or CANCELED tasks that are not COMPLETED (or reach other terminal states)
        // on further executions.
        // // Eg: for Seq of tasks task1.CANCELED, task1.COMPLETED, task1 shouldn't be retried.
        // Throw an exception if there are no FAILED tasks.
        // Handle JOIN task CANCELED status as special case.
        Map<String, TaskModel> retriableMap = new HashMap<>();
        for (TaskModel task : workflow.getTasks()) {
            switch (task.getStatus()) {
                case FAILED:
                case FAILED_WITH_TERMINAL_ERROR:
                case TIMED_OUT:
                    retriableMap.put(task.getReferenceTaskName(), task);
                    break;
                case CANCELED:
                    if (task.getTaskType().equalsIgnoreCase(TaskType.JOIN.toString())
                            || task.getTaskType().equalsIgnoreCase(TaskType.DO_WHILE.toString())) {
                        task.setStatus(IN_PROGRESS);
                        // Task doesn't have to be updated yet. Will be updated along with other
                        // Workflow tasks downstream.
                    } else {
                        retriableMap.put(task.getReferenceTaskName(), task);
                    }
                    break;
                default:
                    retriableMap.remove(task.getReferenceTaskName());
                    break;
            }
        }

        // if workflow TIMED_OUT due to timeoutSeconds configured in the workflow definition,
        // it may not have any unsuccessful tasks that can be retried
        if (retriableMap.values().size() == 0
                && workflow.getStatus() != WorkflowModel.Status.TIMED_OUT) {
            throw new ApplicationException(
                    CONFLICT,
                    "There are no retryable tasks! Use restart if you want to attempt entire workflow execution again.");
        }

        // Update Workflow with new status.
        // This should load Workflow from archive, if archived.
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setLastRetriedTime(System.currentTimeMillis());
        String lastReasonForIncompletion = workflow.getReasonForIncompletion();
        workflow.setReasonForIncompletion(null);
        // Add to decider queue
        queueDAO.push(
                Utils.DECIDER_QUEUE,
                workflow.getWorkflowId(),
                workflow.getPriority(),
                properties.getWorkflowOffsetTimeout().getSeconds());
        executionDAOFacade.updateWorkflow(workflow);
        LOGGER.info(
                "Workflow {} that failed due to '{}' was retried",
                workflow.toShortString(),
                lastReasonForIncompletion);

        // taskToBeRescheduled would set task `retried` to true, and hence it's important to
        // updateTasks after obtaining task copy from taskToBeRescheduled.
        final WorkflowModel finalWorkflow = workflow;
        List<TaskModel> retriableTasks =
                retriableMap.values().stream()
                        .sorted(Comparator.comparingInt(TaskModel::getSeq))
                        .map(task -> taskToBeRescheduled(finalWorkflow, task))
                        .collect(Collectors.toList());

        dedupAndAddTasks(workflow, retriableTasks);
        // Note: updateTasks before updateWorkflow might fail when Workflow is archived and doesn't
        // exist in primary store.
        executionDAOFacade.updateTasks(workflow.getTasks());
        scheduleTask(workflow, retriableTasks);
    }

    private WorkflowModel findLastFailedSubWorkflowIfAny(
            TaskModel task, WorkflowModel parentWorkflow) {
        if (TaskType.TASK_TYPE_SUB_WORKFLOW.equals(task.getTaskType())
                && UNSUCCESSFUL_TERMINAL_TASK.test(task)) {
            WorkflowModel subWorkflow =
                    executionDAOFacade.getWorkflowModel(task.getSubWorkflowId(), true);
            Optional<TaskModel> taskToRetry =
                    subWorkflow.getTasks().stream().filter(UNSUCCESSFUL_TERMINAL_TASK).findFirst();
            if (taskToRetry.isPresent()) {
                return findLastFailedSubWorkflowIfAny(taskToRetry.get(), subWorkflow);
            }
        }
        return parentWorkflow;
    }

    /**
     * Reschedule a task
     *
     * @param task failed or cancelled task
     * @return new instance of a task with "SCHEDULED" status
     */
    private TaskModel taskToBeRescheduled(WorkflowModel workflow, TaskModel task) {
        TaskModel taskToBeRetried = task.copy();
        taskToBeRetried.setTaskId(IDGenerator.generate());
        taskToBeRetried.setRetriedTaskId(task.getTaskId());
        taskToBeRetried.setStatus(SCHEDULED);
        taskToBeRetried.setRetryCount(task.getRetryCount() + 1);
        taskToBeRetried.setRetried(false);
        taskToBeRetried.setPollCount(0);
        taskToBeRetried.setCallbackAfterSeconds(0);
        taskToBeRetried.setSubWorkflowId(null);
        taskToBeRetried.setScheduledTime(0);
        taskToBeRetried.setStartTime(0);
        taskToBeRetried.setEndTime(0);
        taskToBeRetried.setWorkerId(null);
        taskToBeRetried.setReasonForIncompletion(null);
        taskToBeRetried.setSeq(0);

        // perform parameter replacement for retried task
        Map<String, Object> taskInput =
                parametersUtils.getTaskInput(
                        taskToBeRetried.getWorkflowTask().getInputParameters(),
                        workflow,
                        taskToBeRetried.getWorkflowTask().getTaskDefinition(),
                        taskToBeRetried.getTaskId());
        taskToBeRetried.getInputData().putAll(taskInput);

        task.setRetried(true);
        // since this task is being retried and a retry has been computed, task lifecycle is
        // complete
        task.setExecuted(true);
        return taskToBeRetried;
    }

    private void endExecution(WorkflowModel workflow) {
        Optional<TaskModel> terminateTask =
                workflow.getTasks().stream()
                        .filter(
                                t ->
                                        TaskType.TERMINATE.name().equals(t.getTaskType())
                                                && t.getStatus().isTerminal()
                                                && t.getStatus().isSuccessful())
                        .findFirst();
        if (terminateTask.isPresent()) {
            String terminationStatus =
                    (String)
                            terminateTask
                                    .get()
                                    .getWorkflowTask()
                                    .getInputParameters()
                                    .get(Terminate.getTerminationStatusParameter());
            String reason =
                    String.format(
                            "Workflow is %s by TERMINATE task: %s",
                            terminationStatus, terminateTask.get().getTaskId());
            if (WorkflowModel.Status.FAILED.name().equals(terminationStatus)) {
                workflow.setStatus(WorkflowModel.Status.FAILED);
                workflow = terminate(workflow, new TerminateWorkflowException(reason));
            } else {
                workflow.setReasonForIncompletion(reason);
                workflow = completeWorkflow(workflow);
            }
        } else {
            workflow = completeWorkflow(workflow);
        }
        cancelNonTerminalTasks(workflow);
    }

    /**
     * @param workflow the workflow to be completed
     * @throws ApplicationException if workflow is not in terminal state
     */
    @VisibleForTesting
    WorkflowModel completeWorkflow(WorkflowModel workflow) {
        LOGGER.debug("Completing workflow execution for {}", workflow.getWorkflowId());

        if (workflow.getStatus().equals(WorkflowModel.Status.COMPLETED)) {
            queueDAO.remove(
                    Utils.DECIDER_QUEUE, workflow.getWorkflowId()); // remove from the sweep queue
            executionDAOFacade.removeFromPendingWorkflow(
                    workflow.getWorkflowName(), workflow.getWorkflowId());
            LOGGER.debug("Workflow: {} has already been completed.", workflow.getWorkflowId());
            return workflow;
        }

        if (workflow.getStatus().isTerminal()) {
            String msg =
                    "Workflow is already in terminal state. Current status: "
                            + workflow.getStatus();
            throw new ApplicationException(CONFLICT, msg);
        }

        // FIXME Backwards compatibility for legacy workflows already running.
        // This code will be removed in a future version.
        if (workflow.getWorkflowDefinition() == null) {
            workflow = metadataMapperService.populateWorkflowWithDefinitions(workflow);
        }
        deciderService.updateWorkflowOutput(workflow, null);

        workflow.setStatus(WorkflowModel.Status.COMPLETED);
        workflow.setTasks(workflow.getTasks());
        workflow.setOutput(workflow.getOutput());
        workflow.setReasonForIncompletion(workflow.getReasonForIncompletion());
        workflow.setExternalOutputPayloadStoragePath(
                workflow.getExternalOutputPayloadStoragePath());

        // update the failed reference task names
        workflow.getFailedReferenceTaskNames()
                .addAll(
                        workflow.getTasks().stream()
                                .filter(
                                        t ->
                                                FAILED.equals(t.getStatus())
                                                        || FAILED_WITH_TERMINAL_ERROR.equals(
                                                                t.getStatus()))
                                .map(TaskModel::getReferenceTaskName)
                                .collect(Collectors.toSet()));

        executionDAOFacade.updateWorkflow(workflow);
        LOGGER.debug("Completed workflow execution for {}", workflow.getWorkflowId());
        workflowStatusListener.onWorkflowCompletedIfEnabled(workflow);
        Monitors.recordWorkflowCompletion(
                workflow.getWorkflowName(),
                workflow.getEndTime() - workflow.getCreateTime(),
                workflow.getOwnerApp());

        if (workflow.hasParent()) {
            updateParentWorkflowTask(workflow);
            LOGGER.info(
                    "{} updated parent {} task {}",
                    workflow.toShortString(),
                    workflow.getParentWorkflowId(),
                    workflow.getParentWorkflowTaskId());
            pushParentWorkflow(workflow.getParentWorkflowId());
        }

        executionLockService.releaseLock(workflow.getWorkflowId());
        executionLockService.deleteLock(workflow.getWorkflowId());
        return workflow;
    }

    public void terminateWorkflow(String workflowId, String reason) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        if (WorkflowModel.Status.COMPLETED.equals(workflow.getStatus())) {
            throw new ApplicationException(CONFLICT, "Cannot terminate a COMPLETED workflow.");
        }
        workflow.setStatus(WorkflowModel.Status.TERMINATED);
        terminateWorkflow(workflow, reason, null);
    }

    /**
     * @param workflow the workflow to be terminated
     * @param reason the reason for termination
     * @param failureWorkflow the failure workflow (if any) to be triggered as a result of this
     *     termination
     */
    public WorkflowModel terminateWorkflow(
            WorkflowModel workflow, String reason, String failureWorkflow) {
        try {
            executionLockService.acquireLock(workflow.getWorkflowId(), 60000);

            if (!workflow.getStatus().isTerminal()) {
                workflow.setStatus(WorkflowModel.Status.TERMINATED);
            }

            // FIXME Backwards compatibility for legacy workflows already running.
            // This code will be removed in a future version.
            if (workflow.getWorkflowDefinition() == null) {
                workflow = metadataMapperService.populateWorkflowWithDefinitions(workflow);
            }

            try {
                deciderService.updateWorkflowOutput(workflow, null);
            } catch (Exception e) {
                // catch any failure in this step and continue the execution of terminating workflow
                LOGGER.error(
                        "Failed to update output data for workflow: {}",
                        workflow.getWorkflowId(),
                        e);
                Monitors.error(CLASS_NAME, "terminateWorkflow");
            }

            // update the failed reference task names
            workflow.getFailedReferenceTaskNames()
                    .addAll(
                            workflow.getTasks().stream()
                                    .filter(
                                            t ->
                                                    FAILED.equals(t.getStatus())
                                                            || FAILED_WITH_TERMINAL_ERROR.equals(
                                                                    t.getStatus()))
                                    .map(TaskModel::getReferenceTaskName)
                                    .collect(Collectors.toSet()));

            String workflowId = workflow.getWorkflowId();
            workflow.setReasonForIncompletion(reason);
            executionDAOFacade.updateWorkflow(workflow);
            workflowStatusListener.onWorkflowTerminatedIfEnabled(workflow);
            Monitors.recordWorkflowTermination(
                    workflow.getWorkflowName(), workflow.getStatus(), workflow.getOwnerApp());

            List<TaskModel> tasks = workflow.getTasks();
            try {
                // Remove from the task queue if they were there
                tasks.forEach(
                        task -> queueDAO.remove(QueueUtils.getQueueName(task), task.getTaskId()));
            } catch (Exception e) {
                LOGGER.warn(
                        "Error removing task(s) from queue during workflow termination : {}",
                        workflowId,
                        e);
            }

            if (workflow.hasParent()) {
                updateParentWorkflowTask(workflow);
                LOGGER.info(
                        "{} updated parent {} task {}",
                        workflow.toShortString(),
                        workflow.getParentWorkflowId(),
                        workflow.getParentWorkflowTaskId());
                pushParentWorkflow(workflow.getParentWorkflowId());
            }

            if (!StringUtils.isBlank(failureWorkflow)) {
                Map<String, Object> input = new HashMap<>(workflow.getInput());
                input.put("workflowId", workflowId);
                input.put("reason", reason);
                input.put("failureStatus", workflow.getStatus().toString());

                try {
                    WorkflowDef latestFailureWorkflow =
                            metadataDAO
                                    .getLatestWorkflowDef(failureWorkflow)
                                    .orElseThrow(
                                            () ->
                                                    new RuntimeException(
                                                            "Failure Workflow Definition not found for: "
                                                                    + failureWorkflow));

                    String failureWFId =
                            startWorkflow(
                                    latestFailureWorkflow,
                                    input,
                                    null,
                                    workflowId,
                                    null,
                                    workflow.getTaskToDomain());

                    workflow.getOutput().put("conductor.failure_workflow", failureWFId);
                } catch (Exception e) {
                    LOGGER.error("Failed to start error workflow", e);
                    workflow.getOutput()
                            .put(
                                    "conductor.failure_workflow",
                                    "Error workflow "
                                            + failureWorkflow
                                            + " failed to start.  reason: "
                                            + e.getMessage());
                    Monitors.recordWorkflowStartError(
                            failureWorkflow, WorkflowContext.get().getClientApp());
                }
                executionDAOFacade.updateWorkflow(workflow);
            }
            executionDAOFacade.removeFromPendingWorkflow(
                    workflow.getWorkflowName(), workflow.getWorkflowId());

            List<String> erroredTasks = cancelNonTerminalTasks(workflow);
            if (!erroredTasks.isEmpty()) {
                throw new ApplicationException(
                        Code.INTERNAL_ERROR,
                        String.format(
                                "Error canceling system tasks: %s",
                                String.join(",", erroredTasks)));
            }
            return workflow;
        } finally {
            executionLockService.releaseLock(workflow.getWorkflowId());
            executionLockService.deleteLock(workflow.getWorkflowId());
        }
    }

    /**
     * @param taskResult the task result to be updated
     * @throws ApplicationException
     */
    public void updateTask(TaskResult taskResult) {
        if (taskResult == null) {
            throw new ApplicationException(
                    ApplicationException.Code.INVALID_INPUT, "Task object is null");
        }

        String workflowId = taskResult.getWorkflowInstanceId();
        WorkflowModel workflowInstance = executionDAOFacade.getWorkflowModel(workflowId, true);

        // FIXME Backwards compatibility for legacy workflows already running.
        // This code will be removed in a future version.
        if (workflowInstance.getWorkflowDefinition() == null) {
            workflowInstance =
                    metadataMapperService.populateWorkflowWithDefinitions(workflowInstance);
        }

        TaskModel task =
                Optional.ofNullable(executionDAOFacade.getTaskModel(taskResult.getTaskId()))
                        .orElseThrow(
                                () ->
                                        new ApplicationException(
                                                ApplicationException.Code.NOT_FOUND,
                                                "No such task found by id: "
                                                        + taskResult.getTaskId()));

        LOGGER.debug("Task: {} belonging to Workflow {} being updated", task, workflowInstance);

        String taskQueueName = QueueUtils.getQueueName(task);

        if (task.getStatus().isTerminal()) {
            // Task was already updated....
            queueDAO.remove(taskQueueName, taskResult.getTaskId());
            LOGGER.info(
                    "Task: {} has already finished execution with status: {} within workflow: {}. Removed task from queue: {}",
                    task.getTaskId(),
                    task.getStatus(),
                    task.getWorkflowInstanceId(),
                    taskQueueName);
            Monitors.recordUpdateConflict(
                    task.getTaskType(), workflowInstance.getWorkflowName(), task.getStatus());
            return;
        }

        if (workflowInstance.getStatus().isTerminal()) {
            // Workflow is in terminal state
            queueDAO.remove(taskQueueName, taskResult.getTaskId());
            LOGGER.info(
                    "Workflow: {} has already finished execution. Task update for: {} ignored and removed from Queue: {}.",
                    workflowInstance,
                    taskResult.getTaskId(),
                    taskQueueName);
            Monitors.recordUpdateConflict(
                    task.getTaskType(),
                    workflowInstance.getWorkflowName(),
                    workflowInstance.getStatus());
            return;
        }

        // for system tasks, setting to SCHEDULED would mean restarting the task which is
        // undesirable
        // for worker tasks, set status to SCHEDULED and push to the queue
        if (!systemTaskRegistry.isSystemTask(task.getTaskType())
                && taskResult.getStatus() == TaskResult.Status.IN_PROGRESS) {
            task.setStatus(SCHEDULED);
        } else {
            task.setStatus(TaskModel.Status.valueOf(taskResult.getStatus().name()));
        }
        task.setOutputMessage(taskResult.getOutputMessage());
        task.setReasonForIncompletion(taskResult.getReasonForIncompletion());
        task.setWorkerId(taskResult.getWorkerId());
        task.setCallbackAfterSeconds(taskResult.getCallbackAfterSeconds());
        task.setOutputData(taskResult.getOutputData());
        task.setSubWorkflowId(taskResult.getSubWorkflowId());

        if (StringUtils.isNotBlank(taskResult.getExternalOutputPayloadStoragePath())) {
            task.setExternalOutputPayloadStoragePath(
                    taskResult.getExternalOutputPayloadStoragePath());
        }

        if (task.getStatus().isTerminal()) {
            task.setEndTime(System.currentTimeMillis());
        }

        // Update message in Task queue based on Task status
        switch (task.getStatus()) {
            case COMPLETED:
            case CANCELED:
            case FAILED:
            case FAILED_WITH_TERMINAL_ERROR:
            case TIMED_OUT:
                try {
                    queueDAO.remove(taskQueueName, taskResult.getTaskId());
                    LOGGER.debug(
                            "Task: {} removed from taskQueue: {} since the task status is {}",
                            task,
                            taskQueueName,
                            task.getStatus().name());
                } catch (Exception e) {
                    // Ignore exceptions on queue remove as it wouldn't impact task and workflow
                    // execution, and will be cleaned up eventually
                    String errorMsg =
                            String.format(
                                    "Error removing the message in queue for task: %s for workflow: %s",
                                    task.getTaskId(), workflowId);
                    LOGGER.warn(errorMsg, e);
                    Monitors.recordTaskQueueOpError(
                            task.getTaskType(), workflowInstance.getWorkflowName());
                }
                break;
            case IN_PROGRESS:
            case SCHEDULED:
                try {
                    String postponeTaskMessageDesc =
                            "Postponing Task message in queue for taskId: " + task.getTaskId();
                    String postponeTaskMessageOperation = "postponeTaskMessage";

                    new RetryUtil<>()
                            .retryOnException(
                                    () -> {
                                        // postpone based on callbackAfterSeconds
                                        long callBack = taskResult.getCallbackAfterSeconds();
                                        queueDAO.postpone(
                                                taskQueueName,
                                                task.getTaskId(),
                                                task.getWorkflowPriority(),
                                                callBack);
                                        LOGGER.debug(
                                                "Task: {} postponed in taskQueue: {} since the task status is {} with callbackAfterSeconds: {}",
                                                task,
                                                taskQueueName,
                                                task.getStatus().name(),
                                                callBack);
                                        return null;
                                    },
                                    null,
                                    null,
                                    2,
                                    postponeTaskMessageDesc,
                                    postponeTaskMessageOperation);
                } catch (Exception e) {
                    // Throw exceptions on queue postpone, this would impact task execution
                    String errorMsg =
                            String.format(
                                    "Error postponing the message in queue for task: %s for workflow: %s",
                                    task.getTaskId(), workflowId);
                    LOGGER.error(errorMsg, e);
                    Monitors.recordTaskQueueOpError(
                            task.getTaskType(), workflowInstance.getWorkflowName());
                    throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR, e);
                }
                break;
            default:
                break;
        }

        // Throw an ApplicationException if below operations fail to avoid workflow inconsistencies.
        try {
            String updateTaskDesc = "Updating Task with taskId: " + task.getTaskId();
            String updateTaskOperation = "updateTask";

            new RetryUtil<>()
                    .retryOnException(
                            () -> {
                                executionDAOFacade.updateTask(task);
                                return null;
                            },
                            null,
                            null,
                            2,
                            updateTaskDesc,
                            updateTaskOperation);
        } catch (Exception e) {
            String errorMsg =
                    String.format(
                            "Error updating task: %s for workflow: %s",
                            task.getTaskId(), workflowId);
            LOGGER.error(errorMsg, e);
            Monitors.recordTaskUpdateError(task.getTaskType(), workflowInstance.getWorkflowName());
            throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR, e);
        }

        taskResult.getLogs().forEach(taskExecLog -> taskExecLog.setTaskId(task.getTaskId()));
        executionDAOFacade.addTaskExecLog(taskResult.getLogs());

        if (task.getStatus().isTerminal()) {
            long duration = getTaskDuration(0, task);
            long lastDuration = task.getEndTime() - task.getStartTime();
            Monitors.recordTaskExecutionTime(
                    task.getTaskDefName(), duration, true, task.getStatus());
            Monitors.recordTaskExecutionTime(
                    task.getTaskDefName(), lastDuration, false, task.getStatus());
        }

        decide(workflowId);
    }

    public TaskModel getTask(String taskId) {
        return Optional.ofNullable(executionDAOFacade.getTaskModel(taskId))
                .map(
                        task -> {
                            if (task.getWorkflowTask() != null) {
                                return metadataMapperService.populateTaskWithDefinition(task);
                            }
                            return task;
                        })
                .orElse(null);
    }

    public List<Workflow> getRunningWorkflows(String workflowName, int version) {
        return executionDAOFacade.getPendingWorkflowsByName(workflowName, version);
    }

    public List<String> getWorkflows(String name, Integer version, Long startTime, Long endTime) {
        return executionDAOFacade.getWorkflowsByName(name, startTime, endTime).stream()
                .filter(workflow -> workflow.getWorkflowVersion() == version)
                .map(Workflow::getWorkflowId)
                .collect(Collectors.toList());
    }

    public List<String> getRunningWorkflowIds(String workflowName, int version) {
        return executionDAOFacade.getRunningWorkflowIds(workflowName, version);
    }

    /**
     * @param workflowId ID of the workflow to evaluate the state for
     * @return true if the workflow has completed (success or failed), false otherwise.
     * @throws ApplicationException If there was an error - caller should retry in this case.
     */
    public boolean decide(String workflowId) {
        if (!executionLockService.acquireLock(workflowId)) {
            return false;
        }

        // If it is a new workflow, the tasks will be still empty even though include tasks is true
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);

        // FIXME Backwards compatibility for legacy workflows already running.
        // This code will be removed in a future version.
        workflow = metadataMapperService.populateWorkflowWithDefinitions(workflow);

        if (workflow.getStatus().isTerminal()) {
            if (!workflow.getStatus().isSuccessful()) {
                cancelNonTerminalTasks(workflow);
            }
            return true;
        }

        // we find any sub workflow tasks that have changed
        // and change the workflow/task state accordingly
        adjustStateIfSubWorkflowChanged(workflow);

        try {
            DeciderService.DeciderOutcome outcome = deciderService.decide(workflow);
            if (outcome.isComplete) {
                endExecution(workflow);
                return true;
            }

            List<TaskModel> tasksToBeScheduled = outcome.tasksToBeScheduled;
            setTaskDomains(tasksToBeScheduled, workflow);
            List<TaskModel> tasksToBeUpdated = outcome.tasksToBeUpdated;
            boolean stateChanged = false;

            tasksToBeScheduled = dedupAndAddTasks(workflow, tasksToBeScheduled);

            for (TaskModel task : outcome.tasksToBeScheduled) {
                if (systemTaskRegistry.isSystemTask(task.getTaskType())
                        && NON_TERMINAL_TASK.test(task)) {
                    WorkflowSystemTask workflowSystemTask =
                            systemTaskRegistry.get(task.getTaskType());
                    if (!workflowSystemTask.isAsync()
                            && workflowSystemTask.execute(workflow, task, this)) {
                        tasksToBeUpdated.add(task);
                        stateChanged = true;
                    }
                }
            }

            if (!outcome.tasksToBeUpdated.isEmpty() || !tasksToBeScheduled.isEmpty()) {
                executionDAOFacade.updateTasks(tasksToBeUpdated);
                executionDAOFacade.updateWorkflow(workflow);
            }

            stateChanged = scheduleTask(workflow, tasksToBeScheduled) || stateChanged;

            if (stateChanged) {
                decide(workflowId);
            }

        } catch (TerminateWorkflowException twe) {
            LOGGER.info("Execution terminated of workflow: {}", workflowId, twe);
            terminate(workflow, twe);
            return true;
        } catch (RuntimeException e) {
            LOGGER.error("Error deciding workflow: {}", workflowId, e);
            throw e;
        } finally {
            executionLockService.releaseLock(workflowId);
        }
        return false;
    }

    private void adjustStateIfSubWorkflowChanged(WorkflowModel workflow) {
        Optional<TaskModel> changedSubWorkflowTask = findChangedSubWorkflowTask(workflow);
        if (changedSubWorkflowTask.isPresent()) {
            // reset the flag
            TaskModel subWorkflowTask = changedSubWorkflowTask.get();
            subWorkflowTask.setSubworkflowChanged(false);
            executionDAOFacade.updateTask(subWorkflowTask);

            // find all terminal and unsuccessful JOIN tasks and set them to IN_PROGRESS
            if (workflow.getWorkflowDefinition().containsType(TaskType.TASK_TYPE_JOIN)
                    || workflow.getWorkflowDefinition()
                            .containsType(TaskType.TASK_TYPE_FORK_JOIN_DYNAMIC)) {
                // if we are here, then the SUB_WORKFLOW task could be part of a FORK_JOIN or
                // FORK_JOIN_DYNAMIC
                // and the JOIN task(s) needs to be evaluated again, set them to IN_PROGRESS
                workflow.getTasks().stream()
                        .filter(UNSUCCESSFUL_JOIN_TASK)
                        .peek(t -> t.setStatus(TaskModel.Status.IN_PROGRESS))
                        .forEach(executionDAOFacade::updateTask);
            }
        }
    }

    private Optional<TaskModel> findChangedSubWorkflowTask(WorkflowModel workflow) {
        WorkflowDef workflowDef =
                Optional.ofNullable(workflow.getWorkflowDefinition())
                        .orElseGet(
                                () ->
                                        metadataDAO
                                                .getWorkflowDef(
                                                        workflow.getWorkflowName(),
                                                        workflow.getWorkflowVersion())
                                                .orElseThrow(
                                                        () ->
                                                                new ApplicationException(
                                                                        BACKEND_ERROR,
                                                                        "Workflow Definition is not found")));
        if (workflowDef.containsType(TaskType.TASK_TYPE_SUB_WORKFLOW)
                || workflow.getWorkflowDefinition()
                        .containsType(TaskType.TASK_TYPE_FORK_JOIN_DYNAMIC)) {
            return workflow.getTasks().stream()
                    .filter(
                            t ->
                                    t.getTaskType().equals(TaskType.TASK_TYPE_SUB_WORKFLOW)
                                            && t.isSubworkflowChanged()
                                            && !t.isRetried())
                    .findFirst();
        }
        return Optional.empty();
    }

    @VisibleForTesting
    List<String> cancelNonTerminalTasks(WorkflowModel workflow) {
        List<String> erroredTasks = new ArrayList<>();
        // Update non-terminal tasks' status to CANCELED
        for (TaskModel task : workflow.getTasks()) {
            if (!task.getStatus().isTerminal()) {
                // Cancel the ones which are not completed yet....
                task.setStatus(CANCELED);
                if (systemTaskRegistry.isSystemTask(task.getTaskType())) {
                    WorkflowSystemTask workflowSystemTask =
                            systemTaskRegistry.get(task.getTaskType());
                    try {
                        workflowSystemTask.cancel(workflow, task, this);
                    } catch (Exception e) {
                        erroredTasks.add(task.getReferenceTaskName());
                        LOGGER.error(
                                "Error canceling system task:{}/{} in workflow: {}",
                                workflowSystemTask.getTaskType(),
                                task.getTaskId(),
                                workflow.getWorkflowId(),
                                e);
                    }
                }
                executionDAOFacade.updateTask(task);
            }
        }
        if (erroredTasks.isEmpty()) {
            try {
                workflowStatusListener.onWorkflowFinalizedIfEnabled(workflow);
                queueDAO.remove(Utils.DECIDER_QUEUE, workflow.getWorkflowId());
            } catch (Exception e) {
                LOGGER.error(
                        "Error removing workflow: {} from decider queue",
                        workflow.getWorkflowId(),
                        e);
            }
        }
        return erroredTasks;
    }

    @VisibleForTesting
    List<TaskModel> dedupAndAddTasks(WorkflowModel workflow, List<TaskModel> tasks) {
        List<String> tasksInWorkflow =
                workflow.getTasks().stream()
                        .map(task -> task.getReferenceTaskName() + "_" + task.getRetryCount())
                        .collect(Collectors.toList());

        List<TaskModel> dedupedTasks =
                tasks.stream()
                        .filter(
                                task ->
                                        !tasksInWorkflow.contains(
                                                task.getReferenceTaskName()
                                                        + "_"
                                                        + task.getRetryCount()))
                        .collect(Collectors.toList());

        workflow.getTasks().addAll(dedupedTasks);
        return dedupedTasks;
    }

    /** @throws ApplicationException if the workflow cannot be paused */
    public void pauseWorkflow(String workflowId) {
        try {
            executionLockService.acquireLock(workflowId, 60000);
            WorkflowModel.Status status = WorkflowModel.Status.PAUSED;
            WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, false);
            if (workflow.getStatus().isTerminal()) {
                throw new ApplicationException(
                        CONFLICT,
                        "Workflow id " + workflowId + " has ended, status cannot be updated.");
            }
            if (workflow.getStatus().equals(status)) {
                return; // Already paused!
            }
            workflow.setStatus(status);
            executionDAOFacade.updateWorkflow(workflow);
        } finally {
            executionLockService.releaseLock(workflowId);
        }

        // remove from the sweep queue
        // any exceptions can be ignored, as this is not critical to the pause operation
        try {
            queueDAO.remove(Utils.DECIDER_QUEUE, workflowId);
        } catch (Exception e) {
            LOGGER.info(
                    "[pauseWorkflow] Error removing workflow: {} from decider queue",
                    workflowId,
                    e);
        }
    }

    /**
     * @param workflowId the workflow to be resumed
     * @throws IllegalStateException if the workflow is not in PAUSED state
     */
    public void resumeWorkflow(String workflowId) {
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, false);
        if (!workflow.getStatus().equals(WorkflowModel.Status.PAUSED)) {
            throw new IllegalStateException(
                    "The workflow "
                            + workflowId
                            + " is not PAUSED so cannot resume. "
                            + "Current status is "
                            + workflow.getStatus().name());
        }
        workflow.setStatus(WorkflowModel.Status.RUNNING);
        workflow.setLastRetriedTime(System.currentTimeMillis());
        // Add to decider queue
        queueDAO.push(
                Utils.DECIDER_QUEUE,
                workflow.getWorkflowId(),
                workflow.getPriority(),
                properties.getWorkflowOffsetTimeout().getSeconds());
        executionDAOFacade.updateWorkflow(workflow);
        decide(workflowId);
    }

    /**
     * @param workflowId the id of the workflow
     * @param taskReferenceName the referenceName of the task to be skipped
     * @param skipTaskRequest the {@link SkipTaskRequest} object
     * @throws IllegalStateException
     */
    public void skipTaskFromWorkflow(
            String workflowId, String taskReferenceName, SkipTaskRequest skipTaskRequest) {

        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);

        // FIXME Backwards compatibility for legacy workflows already running.
        // This code will be removed in a future version.
        workflow = metadataMapperService.populateWorkflowWithDefinitions(workflow);

        // If the workflow is not running then cannot skip any task
        if (!workflow.getStatus().equals(WorkflowModel.Status.RUNNING)) {
            String errorMsg =
                    String.format(
                            "The workflow %s is not running so the task referenced by %s cannot be skipped",
                            workflowId, taskReferenceName);
            throw new IllegalStateException(errorMsg);
        }

        // Check if the reference name is as per the workflowdef
        WorkflowTask workflowTask =
                workflow.getWorkflowDefinition().getTaskByRefName(taskReferenceName);
        if (workflowTask == null) {
            String errorMsg =
                    String.format(
                            "The task referenced by %s does not exist in the WorkflowDefinition %s",
                            taskReferenceName, workflow.getWorkflowName());
            throw new IllegalStateException(errorMsg);
        }

        // If the task is already started the again it cannot be skipped
        workflow.getTasks()
                .forEach(
                        task -> {
                            if (task.getReferenceTaskName().equals(taskReferenceName)) {
                                String errorMsg =
                                        String.format(
                                                "The task referenced %s has already been processed, cannot be skipped",
                                                taskReferenceName);
                                throw new IllegalStateException(errorMsg);
                            }
                        });

        // Now create a "SKIPPED" task for this workflow
        TaskModel taskToBeSkipped = new TaskModel();
        taskToBeSkipped.setTaskId(IDGenerator.generate());
        taskToBeSkipped.setReferenceTaskName(taskReferenceName);
        taskToBeSkipped.setWorkflowInstanceId(workflowId);
        taskToBeSkipped.setWorkflowPriority(workflow.getPriority());
        taskToBeSkipped.setStatus(SKIPPED);
        taskToBeSkipped.setTaskType(workflowTask.getName());
        taskToBeSkipped.setCorrelationId(workflow.getCorrelationId());
        if (skipTaskRequest != null) {
            taskToBeSkipped.setInputData(skipTaskRequest.getTaskInput());
            taskToBeSkipped.setOutputData(skipTaskRequest.getTaskOutput());
            taskToBeSkipped.setInputMessage(skipTaskRequest.getTaskInputMessage());
            taskToBeSkipped.setOutputMessage(skipTaskRequest.getTaskOutputMessage());
        }
        executionDAOFacade.createTasks(Collections.singletonList(taskToBeSkipped));
        decide(workflowId);
    }

    public WorkflowModel getWorkflow(String workflowId, boolean includeTasks) {
        return executionDAOFacade.getWorkflowModel(workflowId, includeTasks);
    }

    public void addTaskToQueue(TaskModel task) {
        // put in queue
        String taskQueueName = QueueUtils.getQueueName(task);
        if (task.getCallbackAfterSeconds() > 0) {
            queueDAO.push(
                    taskQueueName,
                    task.getTaskId(),
                    task.getWorkflowPriority(),
                    task.getCallbackAfterSeconds());
        } else {
            queueDAO.push(taskQueueName, task.getTaskId(), task.getWorkflowPriority(), 0);
        }
        LOGGER.debug(
                "Added task {} with priority {} to queue {} with call back seconds {}",
                task,
                task.getWorkflowPriority(),
                taskQueueName,
                task.getCallbackAfterSeconds());
    }

    @VisibleForTesting
    void setTaskDomains(List<TaskModel> tasks, WorkflowModel workflow) {
        Map<String, String> taskToDomain = workflow.getTaskToDomain();
        if (taskToDomain != null) {
            // Step 1: Apply * mapping to all tasks, if present.
            String domainstr = taskToDomain.get("*");
            if (StringUtils.isNotBlank(domainstr)) {
                String[] domains = domainstr.split(",");
                tasks.forEach(
                        task -> {
                            // Filter out SystemTask
                            if (!systemTaskRegistry.isSystemTask(task.getTaskType())) {
                                // Check which domain worker is polling
                                // Set the task domain
                                task.setDomain(getActiveDomain(task.getTaskType(), domains));
                            }
                        });
            }
            // Step 2: Override additional mappings.
            tasks.forEach(
                    task -> {
                        if (!systemTaskRegistry.isSystemTask(task.getTaskType())) {
                            String taskDomainstr = taskToDomain.get(task.getTaskType());
                            if (taskDomainstr != null) {
                                task.setDomain(
                                        getActiveDomain(
                                                task.getTaskType(), taskDomainstr.split(",")));
                            }
                        }
                    });
        }
    }

    /**
     * Gets the active domain from the list of domains where the task is to be queued. The domain
     * list must be ordered. In sequence, check if any worker has polled for last
     * `activeWorkerLastPollMs`, if so that is the Active domain. When no active domains are found:
     * <li>If NO_DOMAIN token is provided, return null.
     * <li>Else, return last domain from list.
     *
     * @param taskType the taskType of the task for which active domain is to be found
     * @param domains the array of domains for the task. (Must contain atleast one element).
     * @return the active domain where the task will be queued
     */
    @VisibleForTesting
    String getActiveDomain(String taskType, String[] domains) {
        if (domains == null || domains.length == 0) {
            return null;
        }

        return Arrays.stream(domains)
                .filter(domain -> !domain.equalsIgnoreCase("NO_DOMAIN"))
                .map(domain -> executionDAOFacade.getTaskPollDataByDomain(taskType, domain.trim()))
                .filter(Objects::nonNull)
                .filter(validateLastPolledTime)
                .findFirst()
                .map(PollData::getDomain)
                .orElse(
                        domains[domains.length - 1].trim().equalsIgnoreCase("NO_DOMAIN")
                                ? null
                                : domains[domains.length - 1].trim());
    }

    private long getTaskDuration(long s, TaskModel task) {
        long duration = task.getEndTime() - task.getStartTime();
        s += duration;
        if (task.getRetriedTaskId() == null) {
            return s;
        }
        return s + getTaskDuration(s, executionDAOFacade.getTaskModel(task.getRetriedTaskId()));
    }

    @VisibleForTesting
    boolean scheduleTask(WorkflowModel workflow, List<TaskModel> tasks) {
        List<TaskModel> createdTasks;
        List<TaskModel> tasksToBeQueued;
        boolean startedSystemTasks = false;

        try {
            if (tasks == null || tasks.isEmpty()) {
                return false;
            }

            // Get the highest seq number
            int count = workflow.getTasks().stream().mapToInt(TaskModel::getSeq).max().orElse(0);

            for (TaskModel task : tasks) {
                if (task.getSeq() == 0) { // Set only if the seq was not set
                    task.setSeq(++count);
                }
            }

            // metric to track the distribution of number of tasks within a workflow
            Monitors.recordNumTasksInWorkflow(
                    workflow.getTasks().size() + tasks.size(),
                    workflow.getWorkflowName(),
                    String.valueOf(workflow.getWorkflowVersion()));

            // Save the tasks in the DAO
            createdTasks = executionDAOFacade.createTasks(tasks);

            List<TaskModel> systemTasks =
                    createdTasks.stream()
                            .filter(task -> systemTaskRegistry.isSystemTask(task.getTaskType()))
                            .collect(Collectors.toList());

            tasksToBeQueued =
                    createdTasks.stream()
                            .filter(task -> !systemTaskRegistry.isSystemTask(task.getTaskType()))
                            .collect(Collectors.toList());

            // Traverse through all the system tasks, start the sync tasks, in case of async queue
            // the tasks
            for (TaskModel task : systemTasks) {
                WorkflowSystemTask workflowSystemTask = systemTaskRegistry.get(task.getTaskType());
                if (workflowSystemTask == null) {
                    throw new ApplicationException(
                            NOT_FOUND, "No system task found by name " + task.getTaskType());
                }
                if (task.getStatus() != null
                        && !task.getStatus().isTerminal()
                        && task.getStartTime() == 0) {
                    task.setStartTime(System.currentTimeMillis());
                }
                if (!workflowSystemTask.isAsync()) {
                    try {
                        workflowSystemTask.start(workflow, task, this);
                    } catch (Exception e) {
                        String errorMsg =
                                String.format(
                                        "Unable to start system task: %s, {id: %s, name: %s}",
                                        task.getTaskType(),
                                        task.getTaskId(),
                                        task.getTaskDefName());
                        throw new ApplicationException(
                                ApplicationException.Code.INTERNAL_ERROR, errorMsg, e);
                    }
                    startedSystemTasks = true;
                    executionDAOFacade.updateTask(task);
                } else {
                    tasksToBeQueued.add(task);
                }
            }
        } catch (Exception e) {
            List<String> taskIds =
                    tasks.stream().map(TaskModel::getTaskId).collect(Collectors.toList());
            String errorMsg =
                    String.format(
                            "Error scheduling tasks: %s, for workflow: %s",
                            taskIds, workflow.getWorkflowId());
            LOGGER.error(errorMsg, e);
            Monitors.error(CLASS_NAME, "scheduleTask");
            throw new TerminateWorkflowException(errorMsg);
        }

        // On addTaskToQueue failures, ignore the exceptions and let WorkflowRepairService take care
        // of republishing the messages to the queue.
        try {
            addTaskToQueue(tasksToBeQueued);
        } catch (Exception e) {
            List<String> taskIds =
                    tasksToBeQueued.stream().map(TaskModel::getTaskId).collect(Collectors.toList());
            String errorMsg =
                    String.format(
                            "Error pushing tasks to the queue: %s, for workflow: %s",
                            taskIds, workflow.getWorkflowId());
            LOGGER.warn(errorMsg, e);
            Monitors.error(CLASS_NAME, "scheduleTask");
        }
        return startedSystemTasks;
    }

    private void addTaskToQueue(final List<TaskModel> tasks) {
        for (TaskModel task : tasks) {
            addTaskToQueue(task);
        }
    }

    private WorkflowModel terminate(
            final WorkflowModel workflow, TerminateWorkflowException terminateWorkflowException) {
        if (!workflow.getStatus().isTerminal()) {
            workflow.setStatus(terminateWorkflowException.getWorkflowStatus());
        }

        String failureWorkflow = workflow.getWorkflowDefinition().getFailureWorkflow();
        if (failureWorkflow != null) {
            if (failureWorkflow.startsWith("$")) {
                String[] paramPathComponents = failureWorkflow.split("\\.");
                String name = paramPathComponents[2]; // name of the input parameter
                failureWorkflow = (String) workflow.getInput().get(name);
            }
        }
        if (terminateWorkflowException.getTask() != null) {
            executionDAOFacade.updateTask(terminateWorkflowException.getTask());
        }
        return terminateWorkflow(
                workflow, terminateWorkflowException.getMessage(), failureWorkflow);
    }

    private boolean rerunWF(
            String workflowId,
            String taskId,
            Map<String, Object> taskInput,
            Map<String, Object> workflowInput,
            String correlationId) {

        // Get the workflow
        WorkflowModel workflow = executionDAOFacade.getWorkflowModel(workflowId, true);
        updateAndPushParents(workflow, "reran");

        // If the task Id is null it implies that the entire workflow has to be rerun
        if (taskId == null) {
            // remove all tasks
            workflow.getTasks().forEach(task -> executionDAOFacade.removeTask(task.getTaskId()));
            // Set workflow as RUNNING
            workflow.setStatus(WorkflowModel.Status.RUNNING);
            // Reset failure reason from previous run to default
            workflow.setReasonForIncompletion(null);
            workflow.setFailedReferenceTaskNames(new HashSet<>());
            if (correlationId != null) {
                workflow.setCorrelationId(correlationId);
            }
            if (workflowInput != null) {
                workflow.setInput(workflowInput);
            }

            queueDAO.push(
                    Utils.DECIDER_QUEUE,
                    workflow.getWorkflowId(),
                    workflow.getPriority(),
                    properties.getWorkflowOffsetTimeout().getSeconds());
            executionDAOFacade.updateWorkflow(workflow);

            decide(workflowId);
            return true;
        }

        // Now iterate through the tasks and find the "specific" task
        TaskModel rerunFromTask = null;
        for (TaskModel task : workflow.getTasks()) {
            if (task.getTaskId().equals(taskId)) {
                rerunFromTask = task;
                break;
            }
        }

        // If not found look into sub workflows
        if (rerunFromTask == null) {
            for (TaskModel task : workflow.getTasks()) {
                if (task.getTaskType().equalsIgnoreCase(TaskType.TASK_TYPE_SUB_WORKFLOW)) {
                    String subWorkflowId = task.getSubWorkflowId();
                    if (rerunWF(subWorkflowId, taskId, taskInput, null, null)) {
                        rerunFromTask = task;
                        break;
                    }
                }
            }
        }

        if (rerunFromTask != null) {
            // set workflow as RUNNING
            workflow.setStatus(WorkflowModel.Status.RUNNING);
            // Reset failure reason from previous run to default
            workflow.setReasonForIncompletion(null);
            workflow.setFailedReferenceTaskNames(new HashSet<>());
            if (correlationId != null) {
                workflow.setCorrelationId(correlationId);
            }
            if (workflowInput != null) {
                workflow.setInput(workflowInput);
            }
            // Add to decider queue
            queueDAO.push(
                    Utils.DECIDER_QUEUE,
                    workflow.getWorkflowId(),
                    workflow.getPriority(),
                    properties.getWorkflowOffsetTimeout().getSeconds());
            executionDAOFacade.updateWorkflow(workflow);
            // update tasks in datastore to update workflow-tasks relationship for archived
            // workflows
            executionDAOFacade.updateTasks(workflow.getTasks());
            // Remove all tasks after the "rerunFromTask"
            for (TaskModel task : workflow.getTasks()) {
                if (task.getSeq() > rerunFromTask.getSeq()) {
                    executionDAOFacade.removeTask(task.getTaskId());
                }
            }
            // reset fields before restarting the task
            rerunFromTask.setScheduledTime(System.currentTimeMillis());
            rerunFromTask.setStartTime(0);
            rerunFromTask.setUpdateTime(0);
            rerunFromTask.setEndTime(0);
            rerunFromTask.getOutputData().clear();
            rerunFromTask.setRetried(false);
            rerunFromTask.setExecuted(false);
            rerunFromTask.setExternalOutputPayloadStoragePath(null);
            if (rerunFromTask.getTaskType().equalsIgnoreCase(TaskType.TASK_TYPE_SUB_WORKFLOW)) {
                // if task is sub workflow set task as IN_PROGRESS and reset start time
                rerunFromTask.setStatus(IN_PROGRESS);
                rerunFromTask.setStartTime(System.currentTimeMillis());
            } else {
                if (taskInput != null) {
                    rerunFromTask.setInputData(taskInput);
                }
                if (systemTaskRegistry.isSystemTask(rerunFromTask.getTaskType())
                        && !systemTaskRegistry.get(rerunFromTask.getTaskType()).isAsync()) {
                    // Start the synchronous system task directly
                    systemTaskRegistry
                            .get(rerunFromTask.getTaskType())
                            .start(workflow, rerunFromTask, this);
                } else {
                    // Set the task to rerun as SCHEDULED
                    rerunFromTask.setStatus(SCHEDULED);
                    addTaskToQueue(rerunFromTask);
                }
            }
            executionDAOFacade.updateTask(rerunFromTask);

            decide(workflowId);
            return true;
        }
        return false;
    }

    public void scheduleNextIteration(TaskModel loopTask, WorkflowModel workflow) {
        // Schedule only first loop over task. Rest will be taken care in Decider Service when this
        // task will get completed.
        List<TaskModel> scheduledLoopOverTasks =
                deciderService.getTasksToBeScheduled(
                        workflow,
                        loopTask.getWorkflowTask().getLoopOver().get(0),
                        loopTask.getRetryCount(),
                        null);
        setTaskDomains(scheduledLoopOverTasks, workflow);
        scheduledLoopOverTasks.forEach(
                t -> {
                    t.setReferenceTaskName(
                            TaskUtils.appendIteration(
                                    t.getReferenceTaskName(), loopTask.getIteration()));
                    t.setIteration(loopTask.getIteration());
                });
        scheduleTask(workflow, scheduledLoopOverTasks);
    }

    public TaskDef getTaskDefinition(TaskModel task) {
        return task.getTaskDefinition()
                .orElseGet(
                        () ->
                                Optional.ofNullable(
                                                metadataDAO.getTaskDef(
                                                        task.getWorkflowTask().getName()))
                                        .orElseThrow(
                                                () -> {
                                                    String reason =
                                                            String.format(
                                                                    "Invalid task specified. Cannot find task by name %s in the task definitions",
                                                                    task.getWorkflowTask()
                                                                            .getName());
                                                    return new TerminateWorkflowException(reason);
                                                }));
    }

    @VisibleForTesting
    void updateParentWorkflowTask(WorkflowModel subWorkflow) {
        TaskModel subWorkflowTask =
                executionDAOFacade.getTaskModel(subWorkflow.getParentWorkflowTaskId());
        executeSubworkflowTaskAndSyncData(subWorkflow, subWorkflowTask);
        executionDAOFacade.updateTask(subWorkflowTask);
    }

    private void executeSubworkflowTaskAndSyncData(
            WorkflowModel subWorkflow, TaskModel subWorkflowTask) {
        WorkflowSystemTask subWorkflowSystemTask =
                systemTaskRegistry.get(TaskType.TASK_TYPE_SUB_WORKFLOW);
        subWorkflowSystemTask.execute(subWorkflow, subWorkflowTask, this);
        // Keep Subworkflow task's data consistent with Subworkflow's.
        if (subWorkflowTask.getStatus().isTerminal()
                && subWorkflowTask.getExternalOutputPayloadStoragePath() != null
                && !subWorkflowTask.getOutputData().isEmpty()) {
            Map<String, Object> parentWorkflowTaskOutputData = subWorkflowTask.getOutputData();
            subWorkflowTask.getOutputData().putAll(parentWorkflowTaskOutputData);
        }
    }

    /** Pushes parent workflow id into the decider queue with a priority. */
    private void pushParentWorkflow(String parentWorkflowId) {
        if (!queueDAO.containsMessage(Utils.DECIDER_QUEUE, parentWorkflowId)) {
            queueDAO.push(Utils.DECIDER_QUEUE, parentWorkflowId, PARENT_WF_PRIORITY, 0);
            LOGGER.info("Pushed parent workflow {} to {}", parentWorkflowId, Utils.DECIDER_QUEUE);
        }
    }
}
