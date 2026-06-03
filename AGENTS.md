# Agent Instructions

Welcome to the microservice architecture project. Currently, we are focusing on implementing the common packages.

When working on this project, all AI agents must adhere to the following guidelines:

## 1. Communication & Clarity
- **Ask First:** If any requirement, design choice, or task is not entirely clear, **ask for clarification** rather than making assumptions or proceeding on your own.

## 2. Coding Standards
- **Composed Method:** Try to apply the Composed Method pattern pragmatically. The focus is not always on keeping functions strictly small, but ensuring they maintain a consistent level of abstraction—describe *what* is being done at a high level rather than *how* the details are implemented within the same function.
- **Conventions:** Follow good coding practices, maintain clean code, and adhere to standard conventions for the respective languages and frameworks in use.

## 3. Documentation Strategy
- **Package-Specific Documentation:** Create a dedicated `.md` documentation file for each significant package in a `docs/` directory (e.g., `/docs/auth.md`, `/docs/rabbitmq.md`, `/docs/common-packages.md`). Currently, we are focusing on the `common-packages`.
  - The documentation **must** include:
    - **What it does:** A clear explanation of the package's purpose and functionality.
    - **Packages used:** A list of external or internal dependencies required.
    - **Why:** The reasoning and context behind the package's implementation choices.

## 4. Implementation Workflow
- **Task Management:** Check `tasks_list.json` and implement features one by one. Maintain the task status precisely as follows:
  - When starting an unassigned task, change its status to `pending`.
  - When finished implementing and documenting, change its status to `awaiting_review`. Do not wait idly; immediately proceed to the next available task while waiting for human approval.
  - Once human approval is received for a task, change its status to `done`.
- **Branching:** For each feature, create a new git branch following the convention `working-on-<featname>`.
- **Documentation Updates:** When you finish implementing a feature, create or update its documentation immediately in the specific package's `.md` file. Do **NOT** document specific package logic inside this `AGENTS.md` file. If you are later asked to change any code, you must also update the corresponding documentation to reflect the changes.
- **Committing:** When you consider a task complete, write good commits. Use short, direct titles and follow the conventional commits format used in the repository (e.g., `feat(domain):`, `chore(domain):`, `fix(domain):`, etc.).
