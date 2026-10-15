variable "dns_zone_id" { type = string }
variable "signaling_hostname" { type = string }
variable "signaling_certificate_arn" { type = string }
variable "turn_hostnames" {
  type = list(string)
  validation {
    condition     = length(var.turn_hostnames) == 2
    error_message = "Informe um hostname TURN por nó e seus certificados nos segredos correspondentes."
  }
}
resource "aws_security_group" "signaling" {
  name_prefix = "${var.name}-signaling-"
  vpc_id      = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "signaling_https" {
  security_group_id = aws_security_group.signaling.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "signaling_backend" {
  security_group_id            = aws_security_group.signaling.id
  referenced_security_group_id = aws_security_group.video.id
  ip_protocol                  = "tcp"
  from_port                    = 7880
  to_port                      = 7880
}
resource "aws_vpc_security_group_ingress_rule" "signaling_backend" {
  security_group_id            = aws_security_group.video.id
  referenced_security_group_id = aws_security_group.signaling.id
  ip_protocol                  = "tcp"
  from_port                    = 7880
  to_port                      = 7880
}
resource "aws_lb" "signaling" {
  name                       = "${var.name}-signaling"
  internal                   = false
  load_balancer_type         = "application"
  subnets                    = var.public_subnet_ids
  security_groups            = [aws_security_group.signaling.id]
  enable_deletion_protection = true
  idle_timeout               = 3600
  drop_invalid_header_fields = true
}
resource "aws_lb_target_group" "signaling" {
  name                 = "${var.name}-signaling"
  port                 = 7880
  protocol             = "HTTP"
  vpc_id               = var.vpc_id
  deregistration_delay = 120
  health_check { path = "/" }
}
resource "aws_lb_target_group_attachment" "signaling" {
  count            = 2
  target_group_arn = aws_lb_target_group.signaling.arn
  target_id        = aws_instance.video[count.index].id
  port             = 7880
}
resource "aws_lb_listener" "signaling" {
  load_balancer_arn = aws_lb.signaling.arn
  port              = 443
  protocol          = "HTTPS"
  certificate_arn   = var.signaling_certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.signaling.arn
  }
}
resource "aws_route53_record" "signaling" {
  zone_id = var.dns_zone_id
  name    = var.signaling_hostname
  type    = "A"
  alias {
    name                   = aws_lb.signaling.dns_name
    zone_id                = aws_lb.signaling.zone_id
    evaluate_target_health = true
  }
}
resource "aws_eip" "video" {
  count  = 2
  domain = "vpc"
}
resource "aws_eip_association" "video" {
  count         = 2
  allocation_id = aws_eip.video[count.index].id
  instance_id   = aws_instance.video[count.index].id
}
resource "aws_route53_record" "turn" {
  count   = 2
  zone_id = var.dns_zone_id
  name    = var.turn_hostnames[count.index]
  type    = "A"
  ttl     = 60
  records = [aws_eip.video[count.index].public_ip]
}
output "livekit_signaling_url" { value = "wss://${var.signaling_hostname}" }
