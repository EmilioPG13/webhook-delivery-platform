# 11. EKS exists only for a short demo window

Status: accepted

## Context

An EKS control plane costs 0.10 USD per hour with no free tier, plus the nodes and a database. Left running it is about
72 USD a month for the control plane alone. This is a portfolio project with a 5 USD monthly budget, on an AWS account
whose credit runs out on 2027-04-09.

## Decision

All Kubernetes work happens on `kind`: the Helm chart, the probes, the rolling update under load, the network policy.
EKS, RDS and the rest exist only inside a switch, `demo_window`, which is off by default. A window is opened with one
`terraform apply`, used, and closed with `terraform destroy` and a manual checklist (clusters, node groups, load
balancers, EBS volumes, RDS and its snapshots, log groups, secrets, images) followed by a look at Cost Explorer the next day.

The AWS account was created at the start of the phase that needed it, not earlier, so the six-month clock of the Free
plan did not run while the code was being written. A budget alert was the first resource created.

## What happened

The window was opened and closed on 2026-10-11. A smoke run on the real services (EKS, RDS 18, the real SQS, Pod
Identity for the master key) accepted 3,001 of 3,001 events and delivered all of them, with no failures. It is one run,
so it is evidence that the stack works there and not a measurement. Cost Explorer, read on 2026-10-12, shows 5.29 USD
of usage on the 11th and 1.46 USD on the 12th (UTC), about 6.75 USD for the window, all covered by credits so the net
cost is 0. The 12th is the tail of the window: the cluster ran 5.1 hours of that day, not 24, and a check of
`us-east-2` afterwards found no cluster, database, instance, volume, address or load balancer. The node type is
`c7i-flex.large`, forced by what the Free plan launches, which made the day more expensive than the plan assumed.
Credits hide this from the budget: it counts credits, so its spend reads 0 and its alerts cannot fire until the credits
are gone.

## Consequences

- The cloud deployment is reproducible Terraform plus a record of one run, not a permanent URL. There is no domain and
  no load balancer; the demo is reached with `kubectl port-forward`.
- The demo video is recorded on `kind`, where it can be redone for free.
- Anything that only EKS can show (Pod Identity, the network policy agent) is checked once, in the window.

## Alternatives considered

- **Leave EKS running.** About 72 USD a month before nodes.
- **k3s on a small EC2 instance.** Kept as the plan B if EKS had been unavailable or too costly. It would not have shown
  Pod Identity or the managed network policy agent.
- **ECS or Fargate.** Learning a second orchestrator adds nothing to the project.
- **A public URL with a load balancer and a certificate.** Needs a domain, which was not worth paying for.
- **No cloud at all.** Possible, but a recorded run on real infrastructure is the strongest evidence the project can
  offer.
