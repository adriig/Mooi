/** An existing GitHub release, or a new one to publish from the default branch. */
export interface ReleaseChoice {
  tag: string;
  create: boolean;
  /** The exact commit the user saw in the release preview. */
  expectedSha?: string;
}
