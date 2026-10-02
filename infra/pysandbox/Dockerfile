FROM python:3.12-slim
RUN pip install --no-cache-dir numpy scipy pandas sympy matplotlib
RUN useradd -m sandbox
USER sandbox
WORKDIR /home/sandbox
