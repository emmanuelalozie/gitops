// Reads are open; writes need "Authorization: Bearer <API_TOKEN>", so only the Apply workflow can change users.
const crypto = require('crypto');

module.exports = (req, res, next) => {
  if (['GET', 'HEAD', 'OPTIONS'].includes(req.method)) {
    return next();
  }
  const expected = Buffer.from(`Bearer ${process.env.API_TOKEN}`);
  const given = Buffer.from(req.get('Authorization') || '');
  if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) {
    return res.status(401).json({ error: 'A valid bearer token is required to change data' });
  }
  return next();
};
