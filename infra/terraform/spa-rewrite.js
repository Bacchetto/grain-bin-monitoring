// CloudFront Function: serve the dashboard's index.html for its routes.
//
// The dashboard is a single-page app: /bins/3 and /alerts are routes inside
// index.html, not files in the S3 bucket. Opening such a URL directly, or
// reloading on it, would ask S3 for a file that does not exist.
//
// This rewrites any request whose last path segment has no file extension to
// /index.html, and lets requests for real files (/assets/index-abc123.js,
// /favicon.svg) through unchanged.
//
// It runs ONLY on the dashboard's cache behaviour, not on /api/*. The usual
// alternative -- a custom error response turning every 403/404 into
// index.html -- applies to the whole distribution, and would replace the
// API's own 404 and 409 Problem Details with the dashboard's HTML.

function handler(event) {
  var request = event.request;
  var lastSegment = request.uri.split('/').pop();

  if (lastSegment.indexOf('.') === -1) {
    request.uri = '/index.html';
  }
  return request;
}
