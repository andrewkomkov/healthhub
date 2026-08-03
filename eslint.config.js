import js from '@eslint/js'
import tseslint from 'typescript-eslint'

/**
 * The lint configuration `npm run lint` has been looking for.
 *
 * The script has existed since the first commit and has never once run: ESLint 9 dropped
 * `.eslintrc` and no flat config was ever written, so `eslint .` exited on "couldn't find an
 * eslint.config file" — and because MegaLinter is what actually runs in CI, nothing noticed.
 * A script in `package.json` that cannot succeed is worse than no script: it is an invitation
 * to conclude the code does not lint cleanly when nobody has asked it.
 *
 * Type-aware rules are deliberately **not** enabled. `npm run typecheck` already runs `tsc`
 * over both workspaces with `strict`, `noUncheckedIndexedAccess`, `noUnusedLocals` and
 * `noUnusedParameters`; adding a second, slower type-checker whose job is to re-derive the same
 * answers buys nothing but a way for the two to disagree. What is here is the part `tsc` does
 * not do: patterns that type-check perfectly well and are still mistakes.
 */
export default tseslint.config(
  {
    // Generated, vendored, or built. `generated-tokens.css` has a Kotlin twin and both come
    // from `packages/design-tokens/tokens.json`; neither is committed, and linting a build
    // output is a way to be told about a defect in a generator by pointing at its output.
    ignores: [
      '**/dist/**',
      '**/node_modules/**',
      '**/build/**',
      // What `wrangler dev` leaves behind: the bundled Worker, its middleware facade and a
      // miniflare state directory. Linting a bundle reports a hundred findings about code
      // nobody wrote, and — worse — it means `npm run lint` passes on a clean checkout and
      // fails the moment somebody has run the dev server. Found exactly that way.
      '**/.wrangler/**',
      '**/.mf/**',
      // Playwright's own output: traces, screenshots and a bundled HTML report.
      '**/test-results/**',
      '**/playwright-report/**',
      'android/**',
      'web/src/core/m3e/generated-tokens.css',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,

  {
    rules: {
      // An unused parameter is often a real oversight, but a leading underscore is how this
      // codebase says "required by the signature, deliberately ignored".
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' },
      ],
      // `any` is a warning rather than an error on purpose. There are a handful of honest ones
      // — the `@types/node` stream-globals cast in `core/telemetry/hht.ts` is documented in
      // AGENT-NOTES — and turning a documented, commented cast into a build failure teaches
      // people to add a disable comment rather than to think about the next one.
      '@typescript-eslint/no-explicit-any': 'warn',
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
    },
  },

  {
    // The Worker's console output is its only observability: there is no log drain and no
    // agent, and `wrangler tail` is what a production problem is diagnosed with.
    files: ['worker/**/*.ts'],
    rules: { 'no-console': 'off' },
  },

  {
    // Build scripts are Node programs whose whole output is what they print — and they run
    // under Node rather than in a browser or a Worker, so they get Node's globals. Declared
    // by name rather than pulled from a `globals` package: it is four identifiers, and a
    // dependency for four identifiers is a dependency to keep up to date for four identifiers.
    files: ['packages/**/*.mjs', '**/*.config.{js,mjs,ts}'],
    languageOptions: {
      globals: {
        console: 'readonly',
        process: 'readonly',
        Buffer: 'readonly',
        URL: 'readonly',
      },
    },
    rules: { 'no-console': 'off' },
  },
)
