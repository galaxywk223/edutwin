import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Trend } from 'k6/metrics';
import { sleep } from 'k6';

const input = JSON.parse(open(__ENV.EDUTWIN_K6_INPUT || '/config/acceptance-data.json'));
const answerTransaction = new Trend('answer_transaction_ms', true);
const eventToSseCompletion = new Trend('event_to_sse_completion_ms', true);

export const options = {
  scenarios: {
    browsing: {
      executor: 'constant-vus',
      exec: 'browse',
      vus: 50,
      duration: '30s',
      gracefulStop: '5s',
    },
    answering: {
      executor: 'per-vu-iterations',
      exec: 'answer',
      vus: 5,
      iterations: 1,
      maxDuration: '45s',
      startTime: '2s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    'http_req_failed': ['rate==0'],
    'http_req_duration{kind:cached}': ['p(95)<1000'],
    answer_transaction_ms: ['p(95)<500'],
    event_to_sse_completion_ms: ['p(95)<5000'],
  },
};

function json(response, context) {
  if (response.status < 200 || response.status >= 300) {
    fail(`${context} returned ${response.status}: ${response.body}`);
  }
  try {
    return response.json();
  } catch (_) {
    fail(`${context} did not return JSON`);
  }
}

function authHeaders(token, additions = {}) {
  return {
    headers: {
      ...(input.hostHeader ? { Host: input.hostHeader } : {}),
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
      ...additions,
    },
  };
}

export function setup() {
  const courses = json(
    http.get(`${input.baseUrl}/api/v1/courses`, authHeaders(input.teacherAccessToken)),
    'course list',
  );
  if (!courses.items || courses.items.length === 0) {
    fail('teacher course list is empty');
  }
  return {
    teacherToken: input.teacherAccessToken,
    courseIds: courses.items.map((item) => item.courseId),
  };
}

export function browse(data) {
  const courseId = data.courseIds[(__VU - 1) % data.courseIds.length];
  const dashboard = http.get(
    `${input.baseUrl}/api/v1/teacher/courses/${courseId}/dashboard`,
    { ...authHeaders(data.teacherToken), tags: { kind: 'cached' } },
  );
  check(dashboard, { 'dashboard cached response is 200': (response) => response.status === 200 });
  sleep(2);
}

export function answer() {
  const account = input.answerAccounts[exec.scenario.iterationInTest % input.answerAccounts.length];
  const questionResponse = http.get(
    `${input.baseUrl}/api/v1/courses/${account.courseId}/practice/next`,
    authHeaders(account.accessToken),
  );
  const question = json(questionResponse, 'next practice question');
  const correctAnswer = input.correctAnswers[question.questionId];
  if (!correctAnswer) {
    fail(`correct answer is unavailable for ${question.questionId}`);
  }
  const key = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const submittedAt = Date.now();
  const submission = http.post(
    `${input.baseUrl}/api/v1/courses/${account.courseId}/answers`,
    JSON.stringify({
      questionId: question.questionId,
      selectedChoiceId: correctAnswer,
      occurredAt: new Date(Date.now() - 100).toISOString(),
    }),
    {
      headers: {
        ...(input.hostHeader ? { Host: input.hostHeader } : {}),
        Authorization: `Bearer ${account.accessToken}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
      },
      tags: { kind: 'answer' },
    },
  );
  answerTransaction.add(submission.timings.duration);
  check(submission, {
    'answer submission is 202': (response) => response.status === 202,
    'answer submission is asynchronous': (response) => response.json('status') === 'QUEUED',
  });
  if (submission.status !== 202) {
    fail(`answer submission failed: ${submission.status} ${submission.body}`);
  }
  const jobId = submission.json('jobId');
  const sse = http.get(
    `${input.baseUrl}/api/v1/analysis/jobs/${jobId}/events`,
    {
      headers: {
        ...(input.hostHeader ? { Host: input.hostHeader } : {}),
        Authorization: `Bearer ${account.accessToken}`,
        Accept: 'text/event-stream',
      },
      responseType: 'text',
      timeout: '15s',
      tags: { kind: 'sse' },
    },
  );
  const elapsed = Date.now() - submittedAt;
  eventToSseCompletion.add(elapsed);
  check(sse, {
    'SSE response is 200': (response) => response.status === 200,
    'SSE reaches completion': (response) => response.body.includes('event:job.completed'),
  });
}
