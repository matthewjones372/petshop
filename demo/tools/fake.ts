/**
 * A stand-in for the tools a real shop would have around it, so the demo's Estate has releases to show: GitHub
 * Actions building each service, Argo CD applying what was built, and Kubernetes running it. None of it is real.
 * The petshop runs on the host and its front end in compose, whatever this says.
 *
 * Every service releases on a cadence of its own, worked out from the clock rather than kept: a build passes, Argo CD
 * applies it a couple of minutes later, and the pods roll to the new image as it does. Estate reads all three, so
 * its cards show the running version, its feed what was built and applied, and its pages what was chosen to run.
 */

/** Minutes are what each service's release takes, from build passed to applied. */
const appliedAfter = 2

const services = [
  {
    name: "petshop",
    replicas: 2,
    // A release every so many minutes, and the commits it is built from, in turn.
    every: 9,
    commits: [
      "Retry the chip registry once before refusing",
      "Name the relay's batch in its span",
      "Count refusals by reason",
      "Keep the outbox's claim inside one transaction",
      "Log the adopter's visit with its trace",
      "Shorter backoff when Kafka is back",
    ],
  },
  {
    name: "petshop-web",
    replicas: 1,
    every: 14,
    commits: [
      "Show a pet's chip on its card",
      "Reconnect /live from now, not from the start",
      "Larger touch targets on the adopt button",
      "Report INP with the page's route",
    ],
  },
] as const

type Service = (typeof services)[number]

const epoch = Date.UTC(2026, 9, 14)
const minute = 60_000

/** A short, stable hex hash, standing in for a commit's sha. */
const shaOf = (text: string) => {
  let hash = 2166136261
  for (const char of text) hash = Math.imul(hash ^ char.charCodeAt(0), 16777619) >>> 0
  return hash.toString(16).padStart(8, "0").slice(0, 7)
}

/** Release `n` of a service: its version, its commit, when its build passed and when Argo CD applied it. */
const release = (service: Service, n: number) => {
  const built = epoch + n * service.every * minute
  const title = service.commits[n % service.commits.length] ?? "Release"
  const sha = shaOf(`${service.name}-${n}`)
  return { n, title, sha, version: `main-${n}-${sha}`, built, applied: built + appliedAfter * minute }
}

/** The latest release whose build has passed, and the latest Argo CD has applied. */
const now = (service: Service, at = Date.now()) => {
  const latest = Math.floor((at - epoch) / (service.every * minute))
  const applied = at >= release(service, latest).applied ? latest : latest - 1
  return { latest: release(service, latest), applied: release(service, applied) }
}

const image = (service: Service, version: string) => `ghcr.io/matthewjones372/${service.name}:${version}`

const json = (body: unknown) => Response.json(body)

/** Argo CD's Application, synced and healthy at the release it last applied. */
const application = (service: Service) => {
  const { applied } = now(service)
  return {
    metadata: { name: service.name },
    status: {
      sync: { status: "Synced", revision: applied.sha },
      health: { status: "Healthy" },
      operationState: { phase: "Succeeded", finishedAt: new Date(applied.applied).toISOString() },
      summary: { images: [image(service, applied.version)] },
      reconciledAt: new Date().toISOString(),
    },
  }
}

/** GitHub Actions' last eight runs of a service's build on main, newest first, the newest still running for a while. */
const runs = (service: Service) => {
  const { latest } = now(service)
  return {
    workflow_runs: Array.from({ length: 8 }, (_, back) => release(service, latest.n - back)).map((each, back) => {
      const running = back === 0 && Date.now() < each.built + minute
      return {
        head_sha: each.sha,
        display_title: each.title,
        status: running ? "in_progress" : "completed",
        conclusion: running ? null : "success",
        updated_at: new Date(each.built).toISOString(),
        html_url: `https://github.com/matthewjones372/petshop/commits/${each.sha}`,
      }
    }),
  }
}

const deployments = () => ({
  items: services.map((service) => ({
    metadata: { name: service.name, namespace: "shop" },
    spec: { replicas: service.replicas, selector: { matchLabels: { app: service.name } } },
  })),
})

/** Each service's pods, on the image Argo CD last applied, started when it did. */
const pods = () => ({
  items: services.flatMap((service) => {
    const { applied } = now(service)
    return Array.from({ length: service.replicas }, (_, index) => ({
      metadata: { name: `${service.name}-${applied.sha.slice(0, 5)}-${"abc"[index]}`, labels: { app: service.name } },
      spec: { nodeName: `node-${index + 1}`, containers: [{ image: image(service, applied.version) }] },
      status: {
        phase: "Running",
        startTime: new Date(applied.applied).toISOString(),
        conditions: [{ type: "Ready", status: "True", lastTransitionTime: new Date(applied.applied).toISOString() }],
        containerStatuses: [{ restartCount: 0 }],
      },
    }))
  }),
})

const named = (name: string | undefined) => services.find((service) => service.name === name)

Bun.serve({
  port: Number(process.env.PORT ?? 8080),
  fetch: (request) => {
    const { pathname } = new URL(request.url)
    const argo = /^\/argo\/api\/v1\/applications\/([^/]+)$/.exec(pathname)
    if (argo !== null) {
      const service = named(decodeURIComponent(argo[1] ?? ""))
      return service === undefined ? new Response("not found", { status: 404 }) : json(application(service))
    }
    const github = /^\/github\/repos\/matthewjones372\/petshop\/actions\/workflows\/([^/]+)\.yml\/runs$/.exec(pathname)
    if (github !== null) {
      const service = named(decodeURIComponent(github[1] ?? ""))
      return service === undefined ? new Response("not found", { status: 404 }) : json(runs(service))
    }
    if (pathname === "/kubernetes/apis/apps/v1/namespaces/shop/deployments") return json(deployments())
    if (pathname === "/kubernetes/api/v1/namespaces/shop/pods") return json(pods())
    return new Response("not found", { status: 404 })
  },
})

console.log("pretending to be GitHub Actions, Argo CD and Kubernetes")
