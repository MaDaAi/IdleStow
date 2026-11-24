## Summary

<!-- What does this change and why? -->

## Safety checklist

- [ ] No new code path mutates user files outside the job engine (`core/jobs`)
- [ ] Authorization, identity revalidation and verification are preserved
- [ ] Tests added/updated; they only use temporary synthetic data
- [ ] `./mvnw verify` and `python -m pytest` pass locally
- [ ] Documentation updated (README / docs) where behaviour changed

## Test evidence

<!-- Paste the commands you ran and their results. -->
