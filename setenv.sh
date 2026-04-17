export $(grep -v '^#' .env | grep -v '^$' | xargs)
